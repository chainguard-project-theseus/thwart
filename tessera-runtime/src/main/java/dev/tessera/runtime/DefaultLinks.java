package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    boolean[] used = new boolean[names.length];
    String expanded = expandPathVariables(path, names, values, used);

    StringBuilder query = new StringBuilder();
    for (int i = 0; i < names.length; i++) {
      if (used[i]) {
        continue;
      }
      List<Object> items = multiValues(values[i]);
      if (items == null) {
        // Like Thymeleaf: null is "" (name=); only a parameter written without a value is bare.
        appendParam(query, names[i], values[i] == null ? "" : values[i]);
      } else {
        for (Object item : items) {
          // A null element of a collection is the bare name, as in Thymeleaf.
          appendParam(query, names[i], item == null ? NO_VALUE : item);
        }
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
    query.append(UrlEncoding.queryParam(name));
    if (value != NO_VALUE) {
      query.append('=').append(UrlEncoding.queryParam(String.valueOf(value)));
    }
  }

  /** The elements of an {@code Iterable} or array, or null for any other value. */
  private static List<Object> multiValues(Object value) {
    List<Object> out = new ArrayList<>();
    if (value instanceof Iterable<?>) {
      for (Object o : (Iterable<?>) value) {
        out.add(o);
      }
    } else if (value instanceof Object[]) {
      out.addAll(Arrays.asList((Object[]) value));
    } else if (value instanceof int[]) {
      for (int v : (int[]) value) {
        out.add(v);
      }
    } else if (value instanceof long[]) {
      for (long v : (long[]) value) {
        out.add(v);
      }
    } else if (value instanceof double[]) {
      for (double v : (double[]) value) {
        out.add(v);
      }
    } else if (value instanceof float[]) {
      for (float v : (float[]) value) {
        out.add(v);
      }
    } else if (value instanceof short[]) {
      for (short v : (short[]) value) {
        out.add(v);
      }
    } else if (value instanceof byte[]) {
      for (byte v : (byte[]) value) {
        out.add(v);
      }
    } else if (value instanceof char[]) {
      for (char v : (char[]) value) {
        out.add(v);
      }
    } else if (value instanceof boolean[]) {
      for (boolean v : (boolean[]) value) {
        out.add(v);
      }
    } else {
      return null;
    }
    return out;
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
    List<Object> items = value == NO_VALUE ? null : multiValues(value);
    if (items == null) {
      return UrlEncoding.pathSegment(checkPathValue(name, value == NO_VALUE ? null : value));
    }
    StringBuilder raw = new StringBuilder();
    StringBuilder encoded = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      String item = items.get(i) == null ? "" : String.valueOf(items.get(i));
      if (i > 0) {
        raw.append(',');
        encoded.append(',');
      }
      raw.append(item);
      encoded.append(UrlEncoding.pathSegment(item));
    }
    checkPathValue(name, raw.toString());
    return encoded.toString();
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
