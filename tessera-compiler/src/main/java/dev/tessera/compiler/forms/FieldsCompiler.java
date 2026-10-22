package dev.tessera.compiler.forms;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The reserved {@code fields} namespace (Thymeleaf's {@code #fields}), valid only inside
 * {@code t:object}: {@code fields.hasErrors('path')} / {@code fields.hasErrors('*')} (boolean)
 * and {@code fields.errors('path')} ({@code List<String>}). Paths must be string literals and
 * are checked against the selection type; a {@code Field} fragment parameter may stand in for the
 * literal. Compiles to {@code ctx.forms().hasErrors(objectName, path)}
 * / {@code ctx.forms().errors(objectName, path)}. The ExpressionCompiler routes every
 * {@code fields.*} and {@code #fields.*} call here.
 */
public final class FieldsCompiler {
  private FieldsCompiler() {}

  public static CompiledExpr compile(
      String function, List<Expr> args, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    var diagnostics = expressions.env().diagnostics();
    if (!function.equals("hasErrors") && !function.equals("errors")) {
      diagnostics.error(pos, "unknown function fields." + function + ": the fields namespace has hasErrors('path') and errors('path')");
      return null;
    }
    Types types = expressions.env().types();
    Elements elements = expressions.env().elements();
    TypeMirror type =
        function.equals("hasErrors")
            ? types.getPrimitiveType(TypeKind.BOOLEAN)
            : types.getDeclaredType(
                elements.getTypeElement("java.util.List"), elements.getTypeElement("java.lang.String").asType());
    // A Field fragment parameter: the caller checked its path against its own t:object.
    Optional<Variable> field = args.size() == 1 ? FormPaths.fieldVariable(args.getFirst(), scope) : Optional.empty();
    if (field.isPresent()) {
      return new CompiledExpr(FormPaths.fromVariable(field.get(), pos, expressions).formsCall(function), type, pos);
    }
    Optional<Scope.Selection> selection = scope.selection();
    if (selection.isEmpty()) {
      diagnostics.error(pos, "fields." + function + "(...) can only be used inside t:object, or with a Field parameter");
      return null;
    }
    if (args.size() != 1 || !(args.getFirst() instanceof Expr.Literal lit) || !(lit.value() instanceof String path)) {
      diagnostics.error(
          pos,
          "fields." + function + " takes one string literal (a field path such as 'lastName', or '*' for all errors)"
              + " or a Field parameter");
      return null;
    }
    if (!path.equals(FormPaths.ALL) && FormPaths.compile(path, lit.pos(), scope, expressions) == null) {
      return null;
    }
    return new CompiledExpr(FormPaths.literal(selection.get(), path, null).formsCall(function), type, pos);
  }
}
