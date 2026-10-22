package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;
import java.util.List;

/** {@code t:fragment="name(Type a, Type b)"}; parentheses optional when there are no params. */
public record FragmentSignature(String name, List<ParamDecl> params, SourcePos pos) {
  public FragmentSignature {
    params = List.copyOf(params);
  }
}
