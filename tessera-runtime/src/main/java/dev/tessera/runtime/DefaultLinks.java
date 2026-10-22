package dev.tessera.runtime;

/**
 * {@link Links} implementing the documented expansion rules for a fixed context path.
 *
 * <p>A path is absolute if it has a scheme ({@code https:}, {@code mailto:}) or starts with
 * {@code //}; it is left as written. A path starting with a single {@code /} gets the context path
 * prepended. Anything else is page-relative and left as written. Query parameters are inserted
 * before a {@code #fragment} and appended with {@code &} if the path already has a query.
 *
 * <p>A path variable value that is {@code null}, empty, {@code .} or {@code ..} is rejected with
 * an {@link IllegalArgumentException}: browsers resolve dot segments even when percent-encoded,
 * and an empty first segment would turn {@code /{x}/y} into the protocol-relative URL {@code //y}.
 */
public final class DefaultLinks implements Links {
  private final String contextPath;

  /** @param contextPath servlet context path, {@code ""} for root; must not end with {@code /} */
  public DefaultLinks(String contextPath) {
    this.contextPath = contextPath;
  }

  public String contextPath() {
    return contextPath;
  }

  @Override
  public String build(String path, String[] names, Object[] values) {
    if (names.length != values.length) {
      throw new IllegalArgumentException("link names and values differ in length");
    }
    boolean[] used = new boolean[names.length];
    String expanded = expandPathVariables(path, names, values, used);

    StringBuilder query = new StringBuilder();
    for (int i = 0; i < names.length; i++) {
      if (used[i]) {
        continue;
      }
      if (!query.isEmpty()) {
        query.append('&');
      }
      query.append(UrlEncoding.queryParam(names[i]));
      if (values[i] != null) {
        query.append('=').append(UrlEncoding.queryParam(String.valueOf(values[i])));
      }
    }

    String fragment = "";
    int hash = expanded.indexOf('#');
    if (hash >= 0) {
      fragment = expanded.substring(hash);
      expanded = expanded.substring(0, hash);
    }
    StringBuilder url = new StringBuilder();
    if (path.startsWith("/") && !path.startsWith("//")) {
      url.append(contextPath);
    }
    url.append(expanded);
    if (!query.isEmpty()) {
      url.append(expanded.indexOf('?') >= 0 ? '&' : '?').append(query);
    }
    return url.append(fragment).toString();
  }

  /** Single pass, so substituted (encoded) values are never re-scanned for {@code {name}}. */
  private static String expandPathVariables(
      String path, String[] names, Object[] values, boolean[] used) {
    StringBuilder sb = new StringBuilder(path.length());
    int i = 0;
    while (i < path.length()) {
      char c = path.charAt(i);
      int close = c == '{' ? path.indexOf('}', i + 1) : -1;
      int index = close < 0 ? -1 : indexOf(names, path.substring(i + 1, close));
      if (index < 0) {
        sb.append(c);
        i++;
        continue;
      }
      sb.append(UrlEncoding.pathSegment(pathValue(names[index], values[index])));
      used[index] = true;
      i = close + 1;
    }
    return sb.toString();
  }

  private static String pathValue(String name, Object value) {
    String s = value == null ? null : String.valueOf(value);
    if (s == null || s.isEmpty() || s.equals(".") || s.equals("..")) {
      throw new IllegalArgumentException(
          "link path variable '"
              + name
              + "' must not be null, empty, '.' or '..' (was "
              + (s == null ? "null" : "'" + s + "'")
              + ")");
    }
    return s;
  }

  private static int indexOf(String[] names, String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return i;
      }
    }
    return -1;
  }
}
