package dev.tessera.runtime;

/**
 * {@link Links} implementing the documented expansion rules for a fixed context path.
 *
 * <p>A path is absolute if it has a scheme ({@code https:}, {@code mailto:}) or starts with
 * {@code //}; it is left as written. A path starting with a single {@code /} gets the context path
 * prepended. Anything else is page-relative and left as written. Query parameters are inserted
 * before a {@code #fragment} and appended with {@code &} if the path already has a query. As in
 * Thymeleaf, a {@code null} query value renders {@code name=}, {@link Links#NO_VALUE} the bare
 * {@code name}, and a collection or array one {@code name=item} per element (a {@code null}
 * element is the bare name; an empty collection renders nothing).
 *
 * <p>Multi-value parameters are traversed once and each element is converted immediately.
 * Values are not snapshotted before rendering: mutations by an iterator or an element's
 * {@code toString()} can affect subsequent elements or invalidate the iteration.
 *
 * <p>A path variable value that is {@code null}, empty, {@code .} or {@code ..} is rejected with
 * an {@link IllegalArgumentException}: browsers resolve dot segments even when percent-encoded,
 * and an empty first segment would turn {@code /{x}/y} into the protocol-relative URL {@code //y}.
 *
 * <p>The context path loses any trailing {@code /} ({@code "/"} is the root, {@code ""}; #358), so
 * {@code /a} never becomes the protocol-relative {@code //a}. One that is neither empty nor a path
 * starting with a single {@code /}, or that has {@code \}, {@code ?}, {@code #}, a space or a
 * control character, is rejected with an {@link IllegalArgumentException}.
 */
public final class DefaultLinks implements Links {
  private final String contextPath;

  /** @param contextPath servlet context path, {@code ""} or {@code "/"} for root */
  public DefaultLinks(String contextPath) {
    this.contextPath = checkContextPath(contextPath);
  }

  /** {@code contextPath} without trailing {@code /}; fails closed on anything but a plain path. */
  private static String checkContextPath(String contextPath) {
    if (contextPath == null) {
      throw new IllegalArgumentException("context path must not be null");
    }
    int end = contextPath.length();
    while (end > 0 && contextPath.charAt(end - 1) == '/') {
      end--;
    }
    String p = contextPath.substring(0, end);
    boolean ok = p.isEmpty() || (p.charAt(0) == '/' && p.charAt(1) != '/');
    for (int i = 0; ok && i < p.length(); i++) {
      char c = p.charAt(i);
      ok = c > ' ' && c != 0x7f && c != '\\' && c != '?' && c != '#';
    }
    if (!ok) {
      throw new IllegalArgumentException(
          "context path must be empty or a path starting with a single '/' (was '" + contextPath + "')");
    }
    return p;
  }

  @Override
  public String build(String path, String[] names, Object[] values) {
    if (names.length != values.length) {
      throw new IllegalArgumentException("link names and values differ in length");
    }
    if (names.length == 0) {
      return path.startsWith("/") && !path.startsWith("//") ? contextPath + path : path;
    }
    return buildWithParameters(path, names, values);
  }

  private String buildWithParameters(String path, String[] names, Object[] values) {
    boolean[] used = new boolean[names.length];
    String expanded = expandPathVariables(path, names, values, used);

    StringBuilder query = new StringBuilder();
    for (int i = 0; i < names.length; i++) {
      if (used[i]) {
        continue;
      }
      String name = names[i];
      if (appendMultiValues(query, name, values[i]) == null) {
        // Like Thymeleaf: null is "" (name=); only a parameter written without a value is bare.
        appendParam(query, UrlEncoding.queryParam(name), values[i] == null ? "" : values[i]);
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
    if (query.length() > 0) {
      url.append(expanded.indexOf('?') >= 0 ? '&' : '?').append(query);
    }
    return url.append(fragment).toString();
  }

  private static void appendParam(StringBuilder query, String name, Object value) {
    if (query.length() > 0) {
      query.append('&');
    }
    query.append(name);
    if (value != NO_VALUE) {
      query.append('=').append(UrlEncoding.queryParam(String.valueOf(value)));
    }
  }

  /**
   * Append an Iterable or array, returning its buffer, or null for a scalar. A null buffer selects
   * path-segment mode. Allocate that buffer and encode query names only when they are needed.
   */
  private static StringBuilder appendMultiValues(StringBuilder out, String name, Object value) {
    boolean path = out == null;
    String encodedName = null;
    if (value instanceof Iterable<?>) {
      out = out == null ? new StringBuilder() : out;
      for (Object item : (Iterable<?>) value) {
        encodedName = appendItem(out, path, name, encodedName, item);
      }
    } else if (value instanceof Object[]) {
      out = out == null ? new StringBuilder() : out;
      for (Object item : (Object[]) value) {
        encodedName = appendItem(out, path, name, encodedName, item);
      }
    } else if (value instanceof int[]) {
      out = out == null ? new StringBuilder() : out;
      for (int item : (int[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof long[]) {
      out = out == null ? new StringBuilder() : out;
      for (long item : (long[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof double[]) {
      out = out == null ? new StringBuilder() : out;
      for (double item : (double[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof float[]) {
      out = out == null ? new StringBuilder() : out;
      for (float item : (float[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof short[]) {
      out = out == null ? new StringBuilder() : out;
      for (short item : (short[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof byte[]) {
      out = out == null ? new StringBuilder() : out;
      for (byte item : (byte[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof char[]) {
      out = out == null ? new StringBuilder() : out;
      for (char item : (char[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else if (value instanceof boolean[]) {
      out = out == null ? new StringBuilder() : out;
      for (boolean item : (boolean[]) value) {
        encodedName = appendItem(out, path, name, encodedName, String.valueOf(item));
      }
    } else {
      return null;
    }
    return out;
  }

  private static String appendItem(
      StringBuilder out, boolean path, String name, String encodedName, Object item) {
    if (path) {
      // A trailing separator also counts empty/null elements; pathValue removes the last one.
      out.append(UrlEncoding.pathSegment(item == null ? "" : String.valueOf(item))).append(',');
    } else {
      // A null element of a collection is the bare name, as in Thymeleaf.
      if (encodedName == null) {
        encodedName = UrlEncoding.queryParam(name);
      }
      appendParam(out, encodedName, item == null ? NO_VALUE : item);
    }
    return encodedName;
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
      sb.append(pathValue(names[index], values[index]));
      used[index] = true;
      i = close + 1;
    }
    return sb.toString();
  }

  /**
   * The encoded path variable. A collection or array fills it with its elements joined by
   * {@code ,} (a {@code null} element is empty), as in Thymeleaf; each element is encoded on its
   * own so the separator stays a literal comma.
   */
  private static String pathValue(String name, Object value) {
    StringBuilder encoded = appendMultiValues(null, null, value);
    if (encoded == null) {
      return UrlEncoding.pathSegment(checkPathValue(name, value == NO_VALUE ? null : value));
    }
    if (encoded.length() > 0) {
      encoded.setLength(encoded.length() - 1);
    }
    // Encoding preserves dots and escapes every other structural character. Only the empty
    // string, "." and ".." can still be empty/dot segments, so no raw joined copy is needed.
    return checkPathValue(name, encoded.toString());
  }

  private static String checkPathValue(String name, Object value) {
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
