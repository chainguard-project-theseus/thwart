package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.SourcePos;
import java.util.Objects;

/** {@code Type name} in {@code t:params} or a fragment signature. */
public final class ParamDecl {
  private final TypeRef type;
  private final String name;
  private final SourcePos pos;

  public ParamDecl(TypeRef type, String name, SourcePos pos) {
    this.type = type;
    this.name = name;
    this.pos = pos;
  }

  public TypeRef type() {
    return type;
  }

  public String name() {
    return name;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ParamDecl)) {
      return false;
    }
    ParamDecl that = (ParamDecl) o;
    return Objects.equals(type, that.type)
        && Objects.equals(name, that.name)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(type);
    h = 31 * h + Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "ParamDecl[type=" + type + ", name=" + name + ", pos=" + pos + "]";
  }
}
