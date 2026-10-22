package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.util.Arrays;
import java.util.Collection;
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
    return switch (target) {
      case null -> 0;
      case Collection<?> c -> c.size();
      case Object[] a -> a.length;
      case Iterable<?> it -> {
        int n = 0;
        for (Object ignored : it) {
          n++;
        }
        yield n;
      }
      default -> throw notAList(target);
    };
  }

  @TemplateFunction
  public static boolean isEmpty(Object target) {
    return switch (target) {
      case null -> true;
      case Collection<?> c -> c.isEmpty();
      case Object[] a -> a.length == 0;
      case Iterable<?> it -> !it.iterator().hasNext();
      default -> throw notAList(target);
    };
  }

  /** {@code true} if {@code target} has an element equal ({@link Objects#equals}) to {@code element}. */
  @TemplateFunction
  public static boolean contains(Object target, Object element) {
    Iterable<?> items =
        switch (target) {
          case null -> java.util.List.of();
          case Collection<?> c -> c;
          case Object[] a -> Arrays.asList(a);
          case Iterable<?> it -> it;
          default -> throw notAList(target);
        };
    for (Object item : items) {
      if (Objects.equals(item, element)) {
        return true;
      }
    }
    return false;
  }

  private static IllegalArgumentException notAList(Object target) {
    return new IllegalArgumentException("not an Iterable or array: " + target.getClass().getName());
  }
}
