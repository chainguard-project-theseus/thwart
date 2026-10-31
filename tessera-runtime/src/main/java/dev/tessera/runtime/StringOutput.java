package dev.tessera.runtime;

/** An {@link Output} that buffers into a {@link StringBuilder}. */
public final class StringOutput implements Output {
  /** Appended to directly by the contextual escapers. */
  final StringBuilder sb;

  public StringOutput() {
    this(256);
  }

  public StringOutput(int capacity) {
    this.sb = new StringBuilder(capacity);
  }

  @Override
  public void write(String s) {
    sb.append(s);
  }

  @Override
  public void write(char c) {
    sb.append(c);
  }

  @Override
  public String toString() {
    return sb.toString();
  }
}
