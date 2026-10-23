package dev.tessera.runtime;

/**
 * Builds URLs for link expressions. {@code @{/owners/{id}/edit(id=o.id, q=term)}} compiles to
 * {@code ctx.links().build("/owners/{id}/edit", new String[] {"id", "q"}, new Object[] {o.getId(), term})}.
 *
 * <p>Semantics (Thymeleaf-compatible): a parameter whose name appears as {@code {name}} in the
 * path is substituted as a path variable, percent-encoded as a path segment; the remaining
 * parameters become query parameters, percent-encoded, in declaration order. A {@code null}
 * query value is emitted as {@code name=}; a parameter written without a value
 * ({@code @{/cart(add, id=x)}}) is passed as {@link #NO_VALUE} and emitted as the bare name. A
 * collection or array value repeats the parameter once per element (a {@code null} element is the
 * bare name) and fills a path variable with its elements joined by {@code ,}. Paths starting with
 * {@code /} are context-relative and get the context path prepended. Absolute URLs are left as
 * written.
 */
@FunctionalInterface
public interface Links {
  /**
   * The value of a link parameter written without one, such as {@code add} in
   * {@code @{/cart(add, id=x)}}: rendered as the bare name, without {@code =}.
   */
  Object NO_VALUE =
      new Object() {
        @Override
        public String toString() {
          return "Links.NO_VALUE";
        }
      };

  String build(String path, String[] names, Object[] values);
}
