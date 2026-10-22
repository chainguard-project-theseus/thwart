package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Built-in {@code strings} namespace (Thymeleaf's {@code #strings}). Always registered.
 *
 * <p>Every function takes its target as {@code Object} and uses its {@code toString()}. A
 * {@code null} target never throws: string-valued functions return {@code null} (which renders as
 * nothing) and boolean-valued ones return {@code false}, except {@link #isEmpty} and
 * {@link #isBlank}, which return {@code true}. Case mapping uses {@link Locale#ROOT}.
 */
@TemplateFunctions("strings")
public final class Strings {
  private Strings() {}

  /** {@code true} if {@code target} is null or only whitespace (Thymeleaf semantics). */
  @TemplateFunction
  public static boolean isEmpty(Object target) {
    return target == null || target.toString().isBlank();
  }

  /** Same as {@link #isEmpty}: {@code true} if {@code target} is null or only whitespace. */
  @TemplateFunction
  public static boolean isBlank(Object target) {
    return isEmpty(target);
  }

  /** {@code target} as a string, or {@code defaultValue} if it {@linkplain #isEmpty is empty}. */
  @TemplateFunction
  public static String defaultString(Object target, String defaultValue) {
    return isEmpty(target) ? defaultValue : target.toString();
  }

  /**
   * {@code target} cut to at most {@code maxSize} characters, ending in {@code ...} when cut.
   *
   * @throws IllegalArgumentException if {@code maxSize < 3}
   */
  @TemplateFunction
  public static String abbreviate(Object target, int maxSize) {
    if (maxSize < 3) {
      throw new IllegalArgumentException("abbreviate maxSize must be at least 3, was " + maxSize);
    }
    if (target == null) {
      return null;
    }
    String s = target.toString();
    return s.length() <= maxSize ? s : s.substring(0, maxSize - 3) + "...";
  }

  @TemplateFunction
  public static String toUpperCase(Object target) {
    return target == null ? null : target.toString().toUpperCase(Locale.ROOT);
  }

  @TemplateFunction
  public static String toLowerCase(Object target) {
    return target == null ? null : target.toString().toLowerCase(Locale.ROOT);
  }

  /** {@code target} with its first character upper-cased. */
  @TemplateFunction
  public static String capitalize(Object target) {
    if (target == null) {
      return null;
    }
    String s = target.toString();
    if (s.isEmpty()) {
      return s;
    }
    int first = s.codePointAt(0);
    return new StringBuilder(s.length())
        .appendCodePoint(Character.toTitleCase(first))
        .append(s, Character.charCount(first), s.length())
        .toString();
  }

  @TemplateFunction
  public static String trim(Object target) {
    return target == null ? null : target.toString().trim();
  }

  @TemplateFunction
  public static boolean contains(Object target, String fragment) {
    return target != null && fragment != null && target.toString().contains(fragment);
  }

  @TemplateFunction
  public static boolean startsWith(Object target, String prefix) {
    return target != null && prefix != null && target.toString().startsWith(prefix);
  }

  @TemplateFunction
  public static boolean endsWith(Object target, String suffix) {
    return target != null && suffix != null && target.toString().endsWith(suffix);
  }

  /** {@code target} from {@code start} to the end; out-of-range indexes throw. */
  @TemplateFunction
  public static String substring(Object target, int start) {
    return target == null ? null : target.toString().substring(start);
  }

  /** {@code target} from {@code start} (inclusive) to {@code end} (exclusive); out-of-range indexes throw. */
  @TemplateFunction
  public static String substring(Object target, int start, int end) {
    return target == null ? null : target.toString().substring(start, end);
  }

  /** Replaces every occurrence of {@code before} with {@code after} (null {@code after} means empty). */
  @TemplateFunction
  public static String replace(Object target, String before, String after) {
    if (target == null) {
      return null;
    }
    String s = target.toString();
    if (before == null || before.isEmpty()) {
      return s;
    }
    return s.replace(before, after == null ? "" : after);
  }

  /**
   * Joins the elements of an {@link Iterable} or object array with {@code separator}. Null
   * elements render as the empty string; a null target returns {@code null}.
   *
   * @throws IllegalArgumentException if {@code target} is neither an {@code Iterable} nor an array
   */
  @TemplateFunction
  public static String join(Object target, String separator) {
    if (target == null) {
      return null;
    }
    Iterable<?> items =
        switch (target) {
          case Iterable<?> it -> it;
          case Object[] array -> java.util.Arrays.asList(array);
          default ->
              throw new IllegalArgumentException(
                  "strings.join needs an Iterable or array, got " + target.getClass().getName());
        };
    StringJoiner joiner = new StringJoiner(separator == null ? "" : separator);
    for (Object item : items) {
      joiner.add(item == null ? "" : item.toString());
    }
    return joiner.toString();
  }
}
