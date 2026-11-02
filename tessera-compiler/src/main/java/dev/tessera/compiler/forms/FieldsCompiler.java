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
 * {@code fields.errors('path')} ({@code List<String>}), and {@code fields.value('path')}, the
 * field's display value as {@code t:field} writes it (a String: Spring's
 * {@code BindStatus.getDisplayValue()}, JSP {@code spring:bind}'s {@code status.value}). Paths are string literals
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
    if (!function.equals("hasErrors") && !function.equals("errors") && !function.equals("value")) {
      diagnostics.error(pos, "unknown function fields." + function + ": the fields namespace has hasErrors('path'),"
          + " errors('path') and value('path')");
      return null;
    }
    Types types = expressions.env().types();
    Elements elements = expressions.env().elements();
    TypeMirror string = elements.getTypeElement("java.lang.String").asType();
    TypeMirror type =
        function.equals("hasErrors")
            ? types.getPrimitiveType(TypeKind.BOOLEAN)
            : function.equals("value") ? string : types.getDeclaredType(elements.getTypeElement("java.util.List"), string);
    // hasErrors and errors read only the errors, never the value, so the field's type need not be
    // view-safe; value passes the field's current value to Forms.value.
    boolean value = function.equals("value");
    FormPaths.Check check = value ? FormPaths.Check.VALUE : FormPaths.Check.PATH;
    // A Field fragment parameter: the caller checked its path against its own t:object.
    Optional<Variable> field = args.size() == 1 ? FormPaths.fieldVariable(args.get(0), scope) : Optional.empty();
    if (field.isPresent()) {
      return call(function, FormPaths.fromVariable(field.get(), pos, expressions), type, pos);
    }
    // A sub-path of one, fields.hasErrors(name.answer).
    Optional<Variable> root = args.size() == 1 ? FormPaths.fieldRoot(args.get(0), scope) : Optional.empty();
    if (root.isPresent()) {
      FormPaths.FormField f = FormPaths.resolveSubPath(args.get(0), root.get(), scope, expressions, check);
      return f == null ? null : call(function, f, type, pos);
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
      if (value) {
        diagnostics.error(pos, "fields.value takes a field path such as 'lastName', not '" + keyword + "': only a field has a"
            + " value");
        return null;
      }
      return new CompiledExpr(FormPaths.literal(selection.get(), keyword, null).formsCall(function), type, pos);
    }
    if (arg instanceof Expr.Literal && ((Expr.Literal) arg).value() instanceof String) {
      String path = (String) ((Expr.Literal) arg).value();
      FormPaths.FormField f = FormPaths.literalField(path, arg.pos(), scope, expressions, check);
      return f == null ? null : call(function, f, type, pos);
    }
    if (arg != null && FormPaths.root(arg) instanceof Expr.SelectionVar) {
      FormPaths.FormField resolved = FormPaths.resolve(arg, scope, expressions, check);
      return resolved == null ? null : call(function, resolved, type, pos);
    }
    diagnostics.error(
        pos,
        "fields." + function + " takes one string literal (a field path such as 'lastName'"
            + (value ? "" : ", '*' for all errors or 'global' for the form object's own") + "),"
            + " a field expression such as *{items[i].name}, or a Field parameter");
    return null;
  }

  /**
   * {@code ctx.forms().hasErrors/errors(objectName, path)}, or for {@code value} the display value
   * {@code ctx.forms().value(objectName, path, currentValue)}, as {@code t:field} writes it.
   */
  private static CompiledExpr call(String function, FormPaths.FormField f, TypeMirror type, SourcePos pos) {
    String code =
        function.equals("value")
            ? "ctx.forms().value(" + f.objectName() + ", " + f.path() + ", " + f.value().code() + ")"
            : f.formsCall(function);
    return new CompiledExpr(code, type, pos);
  }
}
