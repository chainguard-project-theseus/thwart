package dev.tessera.runtime;

/**
 * Iteration status for {@code t:each="o, stat : orders"} (Thymeleaf's status variable).
 * {@code index} is 0-based, {@code count} is 1-based; {@code even}/{@code odd} follow
 * {@code count} as in Thymeleaf.
 */
public final class LoopStatus {
  private final int index;
  private final int count;
  private final int size;
  private final boolean first;
  private final boolean last;
  private final boolean even;
  private final boolean odd;

  public LoopStatus(
      int index, int count, int size, boolean first, boolean last, boolean even, boolean odd) {
    this.index = index;
    this.count = count;
    this.size = size;
    this.first = first;
    this.last = last;
    this.even = even;
    this.odd = odd;
  }

  public static LoopStatus of(int index, int size) {
    int count = index + 1;
    return new LoopStatus(index, count, size, index == 0, count == size, count % 2 == 0, count % 2 != 0);
  }

  public int index() {
    return index;
  }

  public int count() {
    return count;
  }

  public int size() {
    return size;
  }

  public boolean first() {
    return first;
  }

  public boolean last() {
    return last;
  }

  public boolean even() {
    return even;
  }

  public boolean odd() {
    return odd;
  }

  // Thymeleaf-style getters, so templates read stat.index, stat.first, ... as properties.

  public int getIndex() {
    return index;
  }

  public int getCount() {
    return count;
  }

  public int getSize() {
    return size;
  }

  public boolean isFirst() {
    return first;
  }

  public boolean isLast() {
    return last;
  }

  public boolean isEven() {
    return even;
  }

  public boolean isOdd() {
    return odd;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof LoopStatus)) {
      return false;
    }
    LoopStatus other = (LoopStatus) o;
    return index == other.index
        && count == other.count
        && size == other.size
        && first == other.first
        && last == other.last
        && even == other.even
        && odd == other.odd;
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(index, count, size, first, last, even, odd);
  }

  @Override
  public String toString() {
    return "LoopStatus[index=" + index + ", count=" + count + ", size=" + size + ", first=" + first
        + ", last=" + last + ", even=" + even + ", odd=" + odd + "]";
  }
}
