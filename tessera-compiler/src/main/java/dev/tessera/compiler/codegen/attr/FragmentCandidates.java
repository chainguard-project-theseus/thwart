package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

/**
 * What a {@code Fragment}-typed expression may write, as a closed set of candidates the verifier
 * parses where the value lands. A {@code Fragment} parameter of the fragment being generated
 * is a {@link Skeleton.ParamInsert}, bound at each call site; {@code null} writes nothing; a
 * {@code t:with} alias is what its value was ({@link Scope#fragment}); a ternary or Elvis is the
 * union of its branches. Anything else is a
 * build error, also any function or method call whatever its parameter types: one that takes
 * {@code Object} could hand back a template-built value unchecked.
 * A {@code ~{...}} reference is handled where it is written ({@code InsertReplaceProcessor}).
 */
final class FragmentCandidates {
  private FragmentCandidates() {}

  /**
   * The skeleton of what {@code e}, a {@code Fragment}-typed expression in {@code scope}, may
   * write; reports and returns null if it isn't one of the forms above. {@code what} names it in the
   * message.
   */
  static Skeleton.Node of(CodegenContext ctx, Expr e, Scope scope, String what) {
    Skeleton.Node n = resolve(ctx, e, scope);
    if (n == null) {
      ctx.diagnostics()
          .error(
              e.pos(),
              what + " is a Fragment the compiler can't trace to the fragment arguments it may be, so it can't check"
                  + " their markup where it lands: write a Fragment parameter, null, a t:with alias of one, a"
                  + " ~{...} reference, or a ternary or Elvis of those; no function or method call");
    }
    return n;
  }

  /** The node as a sequence. */
  static Skeleton.Seq seq(Skeleton.Node n) {
    return n instanceof Skeleton.Seq ? (Skeleton.Seq) n : new Skeleton.Seq(Collections.singletonList(n));
  }

  private static Skeleton.Node resolve(CodegenContext ctx, Expr e, Scope scope) {
    if (e instanceof Expr.Literal && ((Expr.Literal) e).value() == null) {
      return Skeleton.Seq.empty();
    }
    if (e instanceof Expr.Conditional) {
      Expr.Conditional c = (Expr.Conditional) e;
      // A missing else (${flag ? body}) is null.
      Skeleton.Node otherwise = c.otherwise() == null ? Skeleton.Seq.empty() : resolve(ctx, c.otherwise(), scope);
      return union(resolve(ctx, c.then(), scope), otherwise, e);
    }
    if (e instanceof Expr.Elvis) {
      Expr.Elvis c = (Expr.Elvis) e;
      return union(resolve(ctx, c.value(), scope), resolve(ctx, c.fallback(), scope), e);
    }
    if (e instanceof Expr.Var) {
      return variable(ctx, ((Expr.Var) e).name(), scope, e);
    }
    return null;
  }

  private static Skeleton.Node union(Skeleton.Node a, Skeleton.Node b, Expr e) {
    return a == null || b == null ? null : new Skeleton.Alt(Arrays.asList(seq(a), seq(b)), e.pos());
  }

  private static Skeleton.Node variable(CodegenContext ctx, String name, Scope scope, Expr e) {
    Optional<Variable> v = scope.lookup(name);
    if (!v.isPresent()) {
      return null;
    }
    Optional<Skeleton.Node> alias = scope.fragment(v.get().javaName());
    if (alias.isPresent()) {
      return alias.get();
    }
    if (!v.get().javaName().equals(TemplateGenerator.javaName(name))) {
      // Any other local: a loop variable, or a t:with alias of something else.
      return null;
    }
    // Only a fragment has Fragment parameters: a page may not.
    TemplateIndex.FragmentInfo f = ctx.fragment();
    for (TemplateIndex.Param p : f == null ? Collections.<TemplateIndex.Param>emptyList() : f.params()) {
      if (p.name().equals(name)) {
        return new Skeleton.ParamInsert(name, e.pos());
      }
    }
    return null;
  }
}
