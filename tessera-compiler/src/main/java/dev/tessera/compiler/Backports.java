package dev.tessera.compiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Java 8 versions of the Java 9-11 library methods the compiler used ({@code List.of},
 * {@code List.copyOf}, {@code String.strip}, ...), because the processor runs inside the
 * application's javac, which may be JDK 8. Same contracts: collections are unmodifiable and
 * reject nulls; whitespace is {@link Character#isWhitespace(int)}.
 */
public final class Backports {
  private Backports() {}

  @SafeVarargs
  public static <T> List<T> listOf(T... items) {
    List<T> copy = new ArrayList<>(items.length);
    for (T t : items) {
      copy.add(Objects.requireNonNull(t));
    }
    return Collections.unmodifiableList(copy);
  }

  public static <T> List<T> listCopy(Collection<? extends T> items) {
    List<T> copy = new ArrayList<>(items.size());
    for (T t : items) {
      copy.add(Objects.requireNonNull(t));
    }
    return Collections.unmodifiableList(copy);
  }

  @SafeVarargs
  public static <T> Set<T> setOf(T... items) {
    Set<T> copy = new LinkedHashSet<>();
    for (T t : items) {
      copy.add(Objects.requireNonNull(t));
    }
    return Collections.unmodifiableSet(copy);
  }

  public static <T> Set<T> setCopy(Collection<? extends T> items) {
    Set<T> copy = new LinkedHashSet<>();
    for (T t : items) {
      copy.add(Objects.requireNonNull(t));
    }
    return Collections.unmodifiableSet(copy);
  }

  public static <K, V> Map<K, V> mapCopy(Map<? extends K, ? extends V> map) {
    Map<K, V> copy = new LinkedHashMap<>();
    for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
      copy.put(Objects.requireNonNull(e.getKey()), Objects.requireNonNull(e.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }
}
