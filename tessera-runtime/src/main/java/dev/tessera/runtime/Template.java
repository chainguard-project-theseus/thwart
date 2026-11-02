package dev.tessera.runtime;

/**
 * A compiled template bound to its arguments. Generated classes implement this; there is no
 * way to construct a Template from template source at runtime.
 */
public interface Template {

  /** The view name, e.g. {@code owners/ownerDetails} (template path without {@code .html}). */
  String templateName();

  /** Renders this template to {@code out}. */
  void render(RenderContext ctx, Output out);

  /**
   * Whether the compiler proved this template a {@code FLOW} partial (#396): its output, inserted
   * wherever the build-time verifier accepts a {@code SafeHtml} or {@code Fragment} value, leaves
   * the browser's HTML parser in the state it found it. That is: it is not a page (no doctype or
   * {@code <html>} root); every element it opens it closes; its root isn't a list item
   * ({@code li}, {@code dd}, {@code dt}) outside its own list, a table part, {@code head},
   * {@code body}, {@code html} or {@code frameset}; it has no {@code form}; and no formatting
   * element ({@code b}, {@code i}, {@code em}, ...) is inside another of the same name.
   *
   * <p>{@link SafeHtml#of(Template, RenderContext)} and {@link Fragment#of(Template)} accept only
   * templates that return {@code true}. Generated classes override this when the verifier proves
   * it; nothing else should. A hand-written {@code Template} that returns {@code true} vouches for
   * its output with no check: {@code tessera:review} fails the build on such an override outside
   * generated code.
   */
  default boolean verifiedFlowPartial() {
    return false;
  }

  /** Convenience: renders to a String. */
  default String renderToString(RenderContext ctx) {
    StringOutput out = new StringOutput();
    render(ctx, out);
    return out.toString();
  }
}
