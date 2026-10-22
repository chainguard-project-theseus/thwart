package dev.tessera.compiler.forms;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;

/**
 * Form field paths ({@code lastName}, {@code address.city}) relative to the {@code t:object}
 * selection. Paths are checked at build time against the selection type, so an unknown field
 * is a build error naming the type.
 */
public final class FormPaths {
  private FormPaths() {}

  /** Path meaning "all errors of the form object" for {@code t:errors} and {@code fields.*}. */
  public static final String ALL = "*";

  /**
   * The dotted path of a field expression ({@code lastName}, {@code *{lastName}},
   * {@code *{address.city}}), or null if {@code e} is not a plain property path.
   */
  public static String pathOf(Expr e) {
    return switch (e) {
      case Expr.Var v -> v.name();
      case Expr.SelectionVar s -> s.name();
      case Expr.Property p when !p.nullSafe() -> {
        String target = pathOf(p.target());
        yield target == null ? null : target + "." + p.name();
      }
      default -> null;
    };
  }

  /**
   * Compiles {@code path} against the selection in {@code scope} to the field's current value.
   * Steps after the first are null-safe (a null intermediate object yields null). Reports and
   * returns null on error (no selection, unknown property, bad path).
   */
  public static CompiledExpr compile(String path, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    if (scope.selection().isEmpty()) {
      expressions.env().diagnostics().error(pos, "field '" + path + "' can only be used inside t:object");
      return null;
    }
    Expr e = null;
    for (String segment : path.split("\\.", -1)) {
      if (!isIdentifier(segment)) {
        expressions.env().diagnostics().error(pos, "'" + path + "' is not a field path: use names like lastName or address.city");
        return null;
      }
      e = e == null ? new Expr.SelectionVar(segment, pos) : new Expr.Property(e, segment, true, pos);
    }
    return expressions.compile(e, scope);
  }

  /** Java code for {@code ctx.forms().<method>(objectName, path)}, e.g. {@code errors}. */
  public static String formsCall(String method, Scope.Selection selection, String path) {
    return "ctx.forms()."
        + method
        + "("
        + TemplateGenerator.javaString(selection.objectName())
        + ", "
        + TemplateGenerator.javaString(path)
        + ")";
  }

  private static boolean isIdentifier(String s) {
    if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_') || s.charAt(0) > 127) {
      return false;
    }
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c > 127 || !(Character.isLetterOrDigit(c) || c == '_')) {
        return false;
      }
    }
    return true;
  }
}
