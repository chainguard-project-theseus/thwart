package dev.tessera.compiler.forms;

import dev.tessera.compiler.DiagnosticSink;
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
 * {@code t:object}: {@code fields.hasErrors('path')} / {@code fields.hasErrors('*')} /
 * {@code fields.hasErrors('global')} (the form object's own errors) (boolean) and
 * {@code fields.errors('path')} ({@code List<String>}). Paths are string literals
 * ({@code 'items[0].name'} allowed) checked against the selection type; for an index held in a
 * variable, pass the path as a selection expression, {@code fields.hasErrors(*{items[i].name})}. A
 * {@code Field} fragment parameter, or a sub-path of one ({@code name.answer}), may stand in for the path. Compiles to {@code ctx.forms().hasErrors(objectName, path)}
 * / {@code ctx.forms().errors(objectName, path)}. The ExpressionCompiler routes every
 * {@code fields.*} and {@code #fields.*} call here.
 */
public final class FieldsCompiler {
  private FieldsCompiler() {}

  public static CompiledExpr compile(
      String function, List<Expr> args, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    DiagnosticSink diagnostics = expressions.env().diagnostics();
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
    Optional<Variable> field = args.size() == 1 ? FormPaths.fieldVariable(args.get(0), scope) : Optional.empty();
    if (field.isPresent()) {
      return new CompiledExpr(FormPaths.fromVariable(field.get(), pos, expressions).formsCall(function), type, pos);
    }
    // A sub-path of one, fields.hasErrors(name.answer) (#239).
    Optional<Variable> root = args.size() == 1 ? FormPaths.fieldRoot(args.get(0), scope) : Optional.empty();
    if (root.isPresent()) {
      FormPaths.FormField f = FormPaths.resolveSubPath(args.get(0), root.get(), scope, expressions, FormPaths.Check.PATH);
      return f == null ? null : new CompiledExpr(f.formsCall(function), type, pos);
    }
    Optional<Scope.Selection> selection = scope.selection();
    if (!selection.isPresent()) {
      diagnostics.error(pos, "fields." + function + "(...) can only be used inside t:object, or with a Field parameter");
      return null;
    }
    Expr arg = args.size() == 1 ? args.get(0) : null;
    if (arg instanceof Expr.Literal
        && (FormPaths.ALL.equals(((Expr.Literal) arg).value())
            || FormPaths.GLOBAL.equals(((Expr.Literal) arg).value()))) {
      String keyword = (String) ((Expr.Literal) arg).value();
      return new CompiledExpr(FormPaths.literal(selection.get(), keyword, null).formsCall(function), type, pos);
    }
    if (arg instanceof Expr.Literal && ((Expr.Literal) arg).value() instanceof String) {
      String path = (String) ((Expr.Literal) arg).value();
      // Only the errors are read, never the value, so the field's type need not be view-safe (#129).
      if (FormPaths.literalField(path, arg.pos(), scope, expressions, FormPaths.Check.PATH) == null) {
        return null;
      }
      return new CompiledExpr(FormPaths.literal(selection.get(), path, null).formsCall(function), type, pos);
    }
    if (arg != null && FormPaths.root(arg) instanceof Expr.SelectionVar) {
      FormPaths.FormField resolved = FormPaths.resolve(arg, scope, expressions, FormPaths.Check.PATH);
      return resolved == null ? null : new CompiledExpr(resolved.formsCall(function), type, pos);
    }
    diagnostics.error(
        pos,
        "fields." + function + " takes one string literal (a field path such as 'lastName', '*' for all errors or"
            + " 'global' for the form object's own),"
            + " a field expression such as *{items[i].name}, or a Field parameter");
    return null;
  }
}
