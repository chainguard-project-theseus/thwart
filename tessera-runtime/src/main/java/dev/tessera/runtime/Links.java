package dev.tessera.runtime;

/**
 * The per-request part of link expressions. The compiler turns {@code @{/owners/{id}/edit(id=o.id,
 * q=term)}} into plain string concatenation,
 * {@code ctx.links().encode(ctx.links().contextPath() + "/owners/" + UrlEncoding.pathVariable("id",
 * o.id()) + "/edit?q=" + UrlEncoding.queryValue(term))}; only the context path and the final
 * {@link #encode} hook ({@code response.encodeURL}) come from here.
 *
 * <p>Semantics (Thymeleaf-compatible): a parameter whose name appears as {@code {name}} in the
 * path is substituted as a path variable, percent-encoded as a path segment; the remaining
 * parameters become query parameters, percent-encoded, in declaration order, inserted before a
 * {@code #fragment} and appended with {@code &} if the path already has a query. A {@code null}
 * query value is emitted as {@code name=}; a parameter written without a value
 * ({@code @{/cart(add, id=x)}}) is emitted as the bare name. A collection or array value repeats
 * the parameter once per element (a {@code null} element is the bare name) and fills a path
 * variable with its elements joined by {@code ,}. Paths starting with a single {@code /} are
 * context-relative and get {@link #contextPath} prepended. Other URLs are left as written.
 */
public interface Links {
  /**
   * The context path prepended to a link starting with a single {@code /}: empty for the root, else
   * a path starting with a single {@code /} and not ending in one ({@link DefaultLinks} checks it).
   */
  String contextPath();

  /** Applied to every built link, such as {@code response.encodeURL}; the identity by default. */
  default String encode(String url) {
    return url;
  }
}
