package dev.tessera.runtime;

/**
 * Iteration status for {@code t:each="o, stat : orders"} (Thymeleaf's status variable).
 * {@code index} is 0-based, {@code count} is 1-based; {@code even}/{@code odd} follow
 * {@code count} as in Thymeleaf.
 */
public record LoopStatus(
    int index, int count, int size, boolean first, boolean last, boolean even, boolean odd) {

  public static LoopStatus of(int index, int size) {
    int count = index + 1;
    return new LoopStatus(index, count, size, index == 0, count == size, count % 2 == 0, count % 2 != 0);
  }
}
