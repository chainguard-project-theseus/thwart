package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;
import java.util.Objects;

/** An HTML comment, emitted verbatim. Expressions inside comments are not processed. */
public final class Comment implements Node {
  private final String raw;
  private final SourcePos pos;

  public Comment(String raw, SourcePos pos) {
    this.raw = raw;
    this.pos = pos;
  }

  public String raw() {
    return raw;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Comment)) {
      return false;
    }
    Comment that = (Comment) o;
    return Objects.equals(raw, that.raw)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(raw);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "Comment[raw=" + raw + ", pos=" + pos + "]";
  }
}
