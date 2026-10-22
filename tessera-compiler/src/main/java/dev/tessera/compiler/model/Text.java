package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;
import java.util.Objects;

/**
 * Character data exactly as written (including CDATA sections).
 *
 * @param raw source text, not decoded
 * @param context {@link OutputContext#TEXT}, {@link OutputContext#SCRIPT} or
 *     {@link OutputContext#STYLE} (or FORBIDDEN for non-JS script types); decides how
 *     {@code [[...]]} inlined expressions are escaped
 */
public final class Text implements Node {
  private final String raw;
  private final OutputContext context;
  private final SourcePos pos;

  public Text(String raw, OutputContext context, SourcePos pos) {
    this.raw = raw;
    this.context = context;
    this.pos = pos;
  }

  public String raw() {
    return raw;
  }

  public OutputContext context() {
    return context;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Text)) {
      return false;
    }
    Text that = (Text) o;
    return Objects.equals(raw, that.raw)
        && Objects.equals(context, that.context)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(raw);
    h = 31 * h + Objects.hashCode(context);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "Text[raw=" + raw + ", context=" + context + ", pos=" + pos + "]";
  }
}
