package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.SourcePos;
import java.util.List;
import java.util.Objects;

/** {@code t:fragment="name(Type a, Type b)"}; parentheses optional when there are no params. */
public final class FragmentSignature {
  private final String name;
  private final List<ParamDecl> params;
  private final SourcePos pos;

  public FragmentSignature(String name, List<ParamDecl> params, SourcePos pos) {
    this.name = name;
    this.params = Backports.listCopy(params);
    this.pos = pos;
  }

  public String name() {
    return name;
  }

  public List<ParamDecl> params() {
    return params;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FragmentSignature)) {
      return false;
    }
    FragmentSignature that = (FragmentSignature) o;
    return Objects.equals(name, that.name)
        && Objects.equals(params, that.params)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(params);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "FragmentSignature[name=" + name + ", params=" + params + ", pos=" + pos + "]";
  }
}
