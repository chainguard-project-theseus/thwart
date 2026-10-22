package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;
import java.util.Objects;

/** A {@code <!DOCTYPE>}, XML declaration or processing instruction, emitted verbatim. */
public final class DocType implements Node {
  private final String raw;
  private final SourcePos pos;

  public DocType(String raw, SourcePos pos) {
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
    if (!(o instanceof DocType)) {
      return false;
    }
    DocType that = (DocType) o;
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
    return "DocType[raw=" + raw + ", pos=" + pos + "]";
  }
}
