package dev.tessera.runtime;

import java.util.Objects;

/**
 * A piece of markup passed as a fragment argument, e.g. {@code ~{::body}} in
 * {@code t:replace="~{layout :: layout(~{::body}, 'home')}"}; the receiving fragment inserts it
 * with {@code t:insert="template"} or {@code t:replace="template"}.
 *
 * <p>This class is final and has no constructor outside it, so no lambda or subclass is a
 * {@code Fragment} (#581). In Java, use {@link #of(Template)}. Only its values carry the mark
 * {@link #requireVerified} checks where Java hands a template a {@code Fragment}: a page's
 * {@code t:params}, or a generated {@code fragment_<name>} method. {@link #generated} and the
 * generated {@code fragment$<name>} methods are public for generated code in other packages and
 * check nothing: a call from application code is a review point, like
 * {@link SafeHtml#unchecked}.
 */
public final class Fragment {
  /** What a {@link #generated} value writes. */
  @FunctionalInterface
  public interface Body {
    void render(RenderContext ctx, Output out);
  }

  private final Body body;
  /** Set only by {@link #of}: the value renders a template the compiler verified as a FLOW partial. */
  private final boolean verifiedFlow;

  private Fragment(Body body, boolean verifiedFlow) {
    this.body = Objects.requireNonNull(body, "body");
    this.verifiedFlow = verifiedFlow;
  }

  public void render(RenderContext ctx, Output out) {
    body.render(ctx, out);
  }

  /**
   * A {@code Fragment} value that renders {@code template}, for a {@code Fragment} parameter or
   * model attribute. Like {@link SafeHtml#of(Template, RenderContext)}, it accepts only a template
   * the compiler verified as a {@code FLOW} partial ({@link Template#verifiedFlowPartial()}).
   *
   * @throws IllegalArgumentException if it isn't one, naming the template and the fix
   */
  public static Fragment of(Template template) {
    return new Fragment(SafeHtml.requireFlowPartial(template, "Fragment.of")::render, true);
  }

  /**
   * For generated code: a fragment argument a template builds, such as {@code ~{::body}}. The
   * verifier parses its markup where the templates insert it, so it doesn't carry the mark, and
   * {@link #requireVerified} refuses it where Java hands it back to a template. Java code uses
   * {@link #of(Template)}: this is public only for generated classes in other packages, and
   * nothing checks {@code body}, so passed to a generated {@code fragment$<name>} method it writes
   * whatever it writes. A call outside generated code is a review point.
   */
  public static Fragment generated(Body body) {
    return new Fragment(body, false);
  }

  /**
   * For generated code, where Java hands template {@code template} a {@code Fragment} as
   * {@code param}: returns if {@code f} is null or from {@link #of(Template)}, else throws. The
   * verifier checks only that such a value lands where flow content may, so its markup must be a
   * verified FLOW partial (#581).
   *
   * @throws IllegalArgumentException naming the template, the parameter and the fix
   */
  public static void requireVerified(Fragment f, String template, String param) {
    if (f != null && !f.verifiedFlow) {
      throw new IllegalArgumentException(
          "Tessera: template '" + template + "' parameter '" + param + "' needs a Fragment from"
              + " Fragment.of(template), and this one is not, so its markup was never checked where"
              + " it lands here. Fix: pass Fragment.of(...) of a compiled flow partial (docs/guide.md"
              + " section 4.4), or pass the fragment from the template that inserts it (#581)");
    }
  }
}
