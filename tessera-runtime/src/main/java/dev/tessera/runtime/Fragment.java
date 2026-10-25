package dev.tessera.runtime;

/**
 * A piece of markup passed as a fragment argument, e.g. {@code ~{::body}} in
 * {@code t:replace="~{layout :: layout(~{::body}, 'home')}"}. Generated code creates these as
 * lambdas; the receiving fragment inserts it with {@code t:insert="template"} or
 * {@code t:replace="template"}.
 */
@FunctionalInterface
public interface Fragment {
  void render(RenderContext ctx, Output out);

  /**
   * A {@code Fragment} value that renders {@code template}, for a {@code Fragment} parameter or
   * model attribute. Like {@link SafeHtml#of(Template, RenderContext)}, it accepts only a template
   * the compiler verified as a {@code FLOW} partial ({@link Template#verifiedFlowPartial()}).
   * Use this rather than a lambda: a hand-written {@code Fragment} writes whatever it writes, and
   * vouches for it as {@link SafeHtml#unchecked(String)} does.
   *
   * @throws IllegalArgumentException if it isn't one, naming the template and the fix
   */
  static Fragment of(Template template) {
    return SafeHtml.requireFlowPartial(template, "Fragment.of")::render;
  }
}
