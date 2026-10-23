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
 */
public final class DefaultLinks implements Links {
  private final String contextPath;

  /** @param contextPath servlet context path, {@code ""} for root; must not end with {@code /} */
  public DefaultLinks(String contextPath) {
    this.contextPath = contextPath;
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
      String name = UrlEncoding.queryParam(names[i]);
      if (!appendMultiValues(query, name, values[i])) {
        // Like Thymeleaf: null is "" (name=); only a parameter written without a value is bare.
        appendParam(query, name, values[i] == null ? "" : values[i]);
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

  /** Append an Iterable or array without a snapshot; null name selects path-segment mode. */
  private static boolean appendMultiValues(StringBuilder out, String name, Object value) {
    if (value instanceof Iterable<?>) {
      for (Object item : (Iterable<?>) value) {
        appendItem(out, name, item);
      }
    } else if (value instanceof Object[]) {
      for (Object item : (Object[]) value) {
        appendItem(out, name, item);
      }
    } else if (value instanceof int[]) {
      for (int item : (int[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof long[]) {
      for (long item : (long[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof double[]) {
      for (double item : (double[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof float[]) {
      for (float item : (float[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof short[]) {
      for (short item : (short[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof byte[]) {
      for (byte item : (byte[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof char[]) {
      for (char item : (char[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else if (value instanceof boolean[]) {
      for (boolean item : (boolean[]) value) {
        appendItem(out, name, String.valueOf(item));
      }
    } else {
      return false;
    }
    return true;
  }

  private static void appendItem(StringBuilder out, String name, Object item) {
    if (name == null) {
      // A trailing separator also counts empty/null elements; pathValue removes the last one.
      out.append(UrlEncoding.pathSegment(item == null ? "" : String.valueOf(item))).append(',');
    } else {
      // A null element of a collection is the bare name, as in Thymeleaf.
      appendParam(out, name, item == null ? NO_VALUE : item);
    }
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
    StringBuilder encoded = new StringBuilder();
    if (!appendMultiValues(encoded, null, value)) {
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
