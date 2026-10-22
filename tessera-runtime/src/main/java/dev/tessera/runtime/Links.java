package dev.tessera.runtime;

/**
 * Builds URLs for link expressions. {@code @{/owners/{id}/edit(id=o.id, q=term)}} compiles to
 * {@code ctx.links().build("/owners/{id}/edit", new String[] {"id", "q"}, new Object[] {o.getId(), term})}.
 *
 * <p>Semantics (Thymeleaf-compatible): a parameter whose name appears as {@code {name}} in the
 * path is substituted as a path variable, percent-encoded as a path segment; the remaining
 * parameters become query parameters, percent-encoded, in declaration order; {@code null}
 * query values are emitted as the bare name. Paths starting with {@code /} are
 * context-relative and get the context path prepended. Absolute URLs are left as written.
 */
@FunctionalInterface
public interface Links {
  String build(String path, String[] names, Object[] values);
}
