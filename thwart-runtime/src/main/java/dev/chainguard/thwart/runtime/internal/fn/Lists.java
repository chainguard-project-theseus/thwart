package dev.chainguard.thwart.runtime.internal.fn;

import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Built-in {@code lists} namespace (Thymeleaf's {@code #lists}). Always registered.
 *
 * <p>Targets may be any {@link Iterable} or object array; {@code null} is treated as empty.
 * Anything else throws {@link IllegalArgumentException}.
 */
@TemplateFunctions("lists")
public final class Lists {
  private Lists() {}

  @TemplateFunction
  public static int size(Object target) {
    if (target == null) {
      return 0;
    }
    if (target instanceof Collection) {
      return ((Collection<?>) target).size();
    }
    if (target instanceof Object[]) {
      return ((Object[]) target).length;
    }
    int n = 0;
    for (Object ignored : items(target)) {
      n++;
    }
    return n;
  }

  @TemplateFunction
  public static boolean isEmpty(Object target) {
    if (target == null) {
      return true;
    }
    if (target instanceof Collection) {
      return ((Collection<?>) target).isEmpty();
    }
    if (target instanceof Object[]) {
      return ((Object[]) target).length == 0;
    }
    return !items(target).iterator().hasNext();
  }

  /** {@code true} if {@code target} has an element equal ({@link Objects#equals}) to {@code element}. */
  @TemplateFunction
  public static boolean contains(Object target, Object element) {
    for (Object item : items(target)) {
      if (Objects.equals(item, element)) {
        return true;
      }
    }
    return false;
  }

  /** {@code target} as an {@link Iterable}: null is empty, arrays are wrapped, anything else throws. */
  static Iterable<?> items(Object target) {
    if (target == null) {
      return Collections.emptyList();
    }
    if (target instanceof Object[]) {
      return Arrays.asList((Object[]) target);
    }
    if (target instanceof Iterable) {
      return (Iterable<?>) target;
    }
    throw notAList(target);
  }

  private static IllegalArgumentException notAList(Object target) {
    return new IllegalArgumentException("not an Iterable or array: " + target.getClass().getName());
  }
}
