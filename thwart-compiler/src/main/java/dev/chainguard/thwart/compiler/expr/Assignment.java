package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.SourcePos;
import java.util.Objects;

/**
 * {@code name=expr} in {@code t:with} or link parameters. Attribute names may
 * contain {@code - : .}; link parameter names are plain identifiers. A link parameter written
 * without a value ({@code add} in {@code @{/cart(add, id=x)}}) has a {@code null} {@link #value()}.
 */
public final class Assignment {
  private final String name;
  private final Expr value;
  private final SourcePos pos;

  public Assignment(String name, Expr value, SourcePos pos) {
    this.name = name;
    this.value = value;
    this.pos = pos;
  }

  public String name() {
    return name;
  }

  public Expr value() {
    return value;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Assignment)) {
      return false;
    }
    Assignment that = (Assignment) o;
    return Objects.equals(name, that.name)
        && Objects.equals(value, that.value)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(value);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "Assignment[name=" + name + ", value=" + value + ", pos=" + pos + "]";
  }
}
