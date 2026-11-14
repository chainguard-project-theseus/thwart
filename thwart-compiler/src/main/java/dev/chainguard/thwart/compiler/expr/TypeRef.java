package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.SourcePos;
import java.util.List;
import java.util.Objects;

/**
 * A Java type as written in {@code t:params} or a fragment signature, e.g.
 * {@code Map<String, List<OwnerView>>} or {@code int[]}. Resolved by the ExpressionCompiler.
 *
 * @param name simple or qualified name as written
 * @param typeArgs type arguments (no wildcards in the MVP)
 * @param arrayDims number of {@code []}
 */
public final class TypeRef {
  private final String name;
  private final List<TypeRef> typeArgs;
  private final int arrayDims;
  private final SourcePos pos;

  public TypeRef(String name, List<TypeRef> typeArgs, int arrayDims, SourcePos pos) {
    this.name = name;
    this.typeArgs = Backports.listCopy(typeArgs);
    this.arrayDims = arrayDims;
    this.pos = pos;
  }

  public String name() {
    return name;
  }

  public List<TypeRef> typeArgs() {
    return typeArgs;
  }

  public int arrayDims() {
    return arrayDims;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TypeRef)) {
      return false;
    }
    TypeRef that = (TypeRef) o;
    return Objects.equals(name, that.name)
        && Objects.equals(typeArgs, that.typeArgs)
        && arrayDims == that.arrayDims
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(typeArgs);
    h = 31 * h + Integer.hashCode(arrayDims);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "TypeRef[name=" + name + ", typeArgs=" + typeArgs + ", arrayDims=" + arrayDims
        + ", pos=" + pos + "]";
  }
}
