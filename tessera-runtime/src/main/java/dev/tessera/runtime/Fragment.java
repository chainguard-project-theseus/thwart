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
}
