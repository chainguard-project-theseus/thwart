package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Fragment;
import java.util.List;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * {@code t:with="a=expr, b=expr"}: declares locals visible to the element and its children
 * (later assignments may use earlier ones). A name may shadow an outer variable; every local gets
 * a fresh Java name, so shadowing never clashes with an enclosing Java local.
 */
public final class WithProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.value() == null) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " needs a value, e.g. t:with=\"total=order.total\"");
      return;
    }
    List<Assignment> assignments =
        ctx.parser().parseAssignments(attr.value(), attr.valuePos(), ctx.diagnostics());
    if (assignments == null) {
      return;
    }
    Scope scope = plan.scope();
    StringBuilder open = new StringBuilder("{ ");
    for (Assignment a : assignments) {
      if (!FormPaths.isIdentifier(a.name())) {
        ctx.diagnostics()
            .error(a.pos(), "t:with variable name '" + a.name() + "' must be an identifier ([A-Za-z_][A-Za-z0-9_]*)");
        return;
      }
      CompiledExpr value = ctx.expressions().compile(a.value(), scope);
      if (value == null) {
        return;
      }
      Skeleton.Node candidates = null;
      if (ctx.env().types().isAssignable(value.type(), ctx.env().elements().getTypeElement(Fragment.class.getName()).asType())) {
        // An alias of a Fragment is checked where it is inserted as what its value may write (#561).
        candidates = FragmentCandidates.of(ctx, a.value(), scope, "t:with value '" + a.name() + "'");
        if (candidates == null) {
          return;
        }
      }
      String javaName = ctx.freshName(a.name());
      open.append(declaration(value, javaName));
      scope = scope.with(new Variable(a.name(), value.type(), javaName));
      if (candidates != null) {
        scope = scope.withFragment(javaName, candidates);
      }
    }
    plan.wrap(open.toString(), "}");
    plan.scope(scope);
  }

  /** {@code final T name = code;} with the expression's type spelled out (no {@code var}: Java 8). */
  static String declaration(CompiledExpr value, String javaName) {
    return "final " + sourceType(value.type()) + " " + javaName + " = " + value.code() + "; ";
  }

  /** The Java source type of a local of type {@code t}: javac's null type is written {@code java.lang.Object}. */
  static String sourceType(TypeMirror t) {
    return t.getKind() == TypeKind.NULL ? "java.lang.Object" : JavaStrings.typeName(t);
  }
}
