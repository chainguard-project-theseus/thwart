package dev.tessera.runtime;

/**
 * Iteration status for {@code t:each="o, stat : orders"} (Thymeleaf's status variable).
 * {@code index} is 0-based, {@code count} is 1-based; {@code even}/{@code odd} follow
 * {@code count} as in Thymeleaf. Templates read the getters as properties ({@code stat.index},
 * {@code stat.first}, ...).
 */
public final class LoopStatus {
  private final int index;
  private final int size;

  private LoopStatus(int index, int size) {
    this.index = index;
    this.size = size;
  }

  public static LoopStatus of(int index, int size) {
    return new LoopStatus(index, size);
  }

  public int getIndex() {
    return index;
  }

  public int getCount() {
    return index + 1;
  }

  public int getSize() {
    return size;
  }

  public boolean isFirst() {
    return index == 0;
  }

  public boolean isLast() {
    return index + 1 == size;
  }

  public boolean isEven() {
    return (index + 1) % 2 == 0;
  }

  public boolean isOdd() {
    return (index + 1) % 2 != 0;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof LoopStatus && index == ((LoopStatus) o).index && size == ((LoopStatus) o).size;
  }

  @Override
  public int hashCode() {
    return 31 * index + size;
  }

  @Override
  public String toString() {
    return "LoopStatus[index=" + index + ", size=" + size + "]";
  }
}
