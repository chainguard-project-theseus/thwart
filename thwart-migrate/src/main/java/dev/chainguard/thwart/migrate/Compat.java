package dev.chainguard.thwart.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The few post-Java-8 library methods this tool used, written for Java 8: the tool runs on the
 * developer's machine, which may be a JDK 8. Each behaves like the JDK method it names.
 */
final class Compat {
  private Compat() {}

  /** {@code String.strip()}: removes leading and trailing {@link Character#isWhitespace}. */
  static String strip(String s) {
    int start = 0;
    int end = s.length();
    while (start < end) {
      int c = s.codePointAt(start);
      if (!Character.isWhitespace(c)) {
        break;
      }
      start += Character.charCount(c);
    }
    while (end > start) {
      int c = s.codePointBefore(end);
      if (!Character.isWhitespace(c)) {
        break;
      }
      end -= Character.charCount(c);
    }
    return s.substring(start, end);
  }

  /** {@code String.isBlank()}. */
  static boolean isBlank(String s) {
    return strip(s).isEmpty();
  }

  /** {@code String.repeat(n)}. */
  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  /** {@code List.of(...)}. */
  @SafeVarargs
  static <T> List<T> listOf(T... items) {
    List<T> list = new ArrayList<>(items.length);
    for (T item : items) {
      list.add(item);
    }
    return Collections.unmodifiableList(list);
  }

  /** {@code Set.of(...)}. */
  @SafeVarargs
  static <T> Set<T> setOf(T... items) {
    Set<T> set = new HashSet<>();
    for (T item : items) {
      set.add(item);
    }
    return Collections.unmodifiableSet(set);
  }

  /** {@code Map.of(k1, v1, k2, v2, ...)} for string keys and values. */
  static Map<String, String> mapOf(String... keysAndValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return Collections.unmodifiableMap(map);
  }

  /** {@code Files.readString(path, UTF_8)}. */
  static String readString(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /** {@code Files.writeString(path, text, UTF_8)}. */
  static void writeString(Path path, CharSequence text) throws IOException {
    Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8));
  }
}
