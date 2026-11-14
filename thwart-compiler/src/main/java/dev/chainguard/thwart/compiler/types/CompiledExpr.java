package dev.chainguard.thwart.compiler.types;

import dev.chainguard.thwart.compiler.SourcePos;
import java.util.Objects;
import javax.lang.model.type.TypeMirror;

/**
 * A type-checked expression.
 *
 * @param code a Java expression (fully qualified names, no imports needed) that evaluates the
 *     template expression; may reference {@code ctx} (the RenderContext) and variables'
 *     {@code javaName}s
 * @param type its static type
 * @param pos template position of the source expression
 */
public final class CompiledExpr {
  private final String code;
  private final TypeMirror type;
  private final SourcePos pos;

  public CompiledExpr(String code, TypeMirror type, SourcePos pos) {
    this.code = code;
    this.type = type;
    this.pos = pos;
  }

  public String code() {
    return code;
  }

  public TypeMirror type() {
    return type;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CompiledExpr)) {
      return false;
    }
    CompiledExpr that = (CompiledExpr) o;
    return Objects.equals(code, that.code)
        && Objects.equals(type, that.type)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(code);
    h = 31 * h + Objects.hashCode(type);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "CompiledExpr[code=" + code + ", type=" + type + ", pos=" + pos + "]";
  }
}
