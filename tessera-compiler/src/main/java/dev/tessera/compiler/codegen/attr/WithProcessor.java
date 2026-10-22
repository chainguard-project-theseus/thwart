package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.List;
import javax.lang.model.type.TypeKind;

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
      if (!isIdentifier(a.name())) {
        ctx.diagnostics()
            .error(a.pos(), "t:with variable name '" + a.name() + "' must be an identifier ([A-Za-z_][A-Za-z0-9_]*)");
        return;
      }
      CompiledExpr value = ctx.expressions().compile(a.value(), scope);
      if (value == null) {
        return;
      }
      String javaName = ctx.freshName(a.name());
      open.append(declaration(value, javaName));
      scope = scope.with(new Variable(a.name(), value.type(), javaName));
    }
    plan.wrap(open.toString(), "}");
    plan.scope(scope);
  }

  /** {@code final var name = code;} (a {@code null} literal has no {@code var} type). */
  static String declaration(CompiledExpr value, String javaName) {
    String type = value.type().getKind() == TypeKind.NULL ? "java.lang.Object" : "var";
    return "final " + type + " " + javaName + " = " + value.code() + "; ";
  }

  static boolean isIdentifier(String s) {
    if (s.isEmpty() || !(isLetter(s.charAt(0)) || s.charAt(0) == '_')) {
      return false;
    }
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!(isLetter(c) || c == '_' || (c >= '0' && c <= '9'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }
}
