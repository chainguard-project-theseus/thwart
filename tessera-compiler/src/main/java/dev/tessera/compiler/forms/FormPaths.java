package dev.tessera.compiler.forms;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.runtime.Field;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * Form field paths ({@code lastName}, {@code address.city}) relative to the {@code t:object}
 * selection. Paths are checked at build time against the selection type, so an unknown field
 * is a build error naming the type.
 *
 * <p>Inside a fragment, a parameter of type {@code dev.tessera.runtime.Field} stands for a path
 * the caller checked against its own {@code t:object}; {@link FormField} covers both forms.
 */
public final class FormPaths {
  private FormPaths() {}

  /** Path meaning "all errors of the form object" for {@code t:errors} and {@code fields.*}. */
  public static final String ALL = "*";

  /** The runtime type of {@code Field} fragment parameters. */
  public static final String FIELD = Field.class.getName();

  /**
   * A form field resolved at build time.
   *
   * @param objectName Java code (a String) for the form object name
   * @param path Java code (a String) for the dotted field path
   * @param value the field's current value, or null where the caller does not need it
   */
  public static final class FormField {
    private final String objectName;
    private final String path;
    private final CompiledExpr value;

    public FormField(String objectName, String path, CompiledExpr value) {
      this.objectName = objectName;
      this.path = path;
      this.value = value;
    }

    public String objectName() {
      return objectName;
    }

    public String path() {
      return path;
    }

    public CompiledExpr value() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FormField)) {
        return false;
      }
      FormField that = (FormField) o;
      return Objects.equals(objectName, that.objectName)
          && Objects.equals(path, that.path)
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(objectName);
      h = 31 * h + Objects.hashCode(path);
      h = 31 * h + Objects.hashCode(value);
      return h;
    }

    @Override
    public String toString() {
      return "FormField[objectName=" + objectName + ", path=" + path + ", value=" + value + "]";
    }

    /** Java code for {@code ctx.forms().<method>(objectName, path)}, e.g. {@code errors}. */
    public String formsCall(String method) {
      return "ctx.forms()." + method + "(" + objectName + ", " + path + ")";
    }
  
  }

  /** A field given as a literal {@code path} relative to {@code selection}. */
  public static FormField literal(Scope.Selection selection, String path, CompiledExpr value) {
    return new FormField(
        JavaStrings.literal(selection.objectName()), JavaStrings.literal(path), value);
  }

  /**
   * The {@code Field}-typed variable that {@code e} names, if any: {@code e} must be a bare
   * {@link Expr.Var} ({@code name}, or {@code ${name}}, whose wrapper the parser strips). Names
   * containing {@code $} are rejected, so internal variables such as
   * {@code FieldProcessor.SELECTED} ({@code select$value}) cannot be reached from templates. Such a
   * variable wins over a selection property of the same name.
   */
  public static Optional<Variable> fieldVariable(Expr e, Scope scope) {
    if (!(e instanceof Expr.Var) || ((Expr.Var) e).name().contains("$")) {
      return Optional.empty();
    }
    return scope.lookup(((Expr.Var) e).name()).filter(var -> isField(var.type()));
  }

  /** A field held in a {@code Field} variable; its value has static type {@code Object}. */
  public static FormField fromVariable(Variable v, SourcePos pos, ExpressionCompiler expressions) {
    TypeMirror object = expressions.env().elements().getTypeElement("java.lang.Object").asType();
    String j = v.javaName();
    return new FormField(j + ".objectName()", j + ".path()", new CompiledExpr(j + ".value()", object, pos));
  }

  /** Whether {@code t} is the runtime {@code Field} type. */
  public static boolean isField(TypeMirror t) {
    return t != null && ExpressionCompiler.isDeclared(t, FIELD);
  }

  /**
   * The dotted path of a field expression ({@code lastName}, {@code *{lastName}},
   * {@code *{address.city}}), or null if {@code e} is not a plain property path.
   */
  public static String pathOf(Expr e) {
    if (e instanceof Expr.Var) {
      return ((Expr.Var) e).name();
    }
    if (e instanceof Expr.SelectionVar) {
      return ((Expr.SelectionVar) e).name();
    }
    if (e instanceof Expr.Property && !((Expr.Property) e).nullSafe()) {
      Expr.Property p = (Expr.Property) e;
      String target = pathOf(p.target());
      return target == null ? null : target + "." + p.name();
    }
    return null;
  }

  /**
   * Compiles {@code path} against the selection in {@code scope} to the field's current value.
   * Steps after the first are null-safe (a null intermediate object yields null). Reports and
   * returns null on error (no selection, unknown property, bad path).
   */
  public static CompiledExpr compile(String path, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    if (!scope.selection().isPresent()) {
      expressions.env().diagnostics().error(pos, "field '" + path + "' can only be used inside t:object: put t:object=\"...\" on"
              + " this element or an enclosing one");
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

  /** True if {@code s} is an ASCII identifier: {@code [A-Za-z_][A-Za-z0-9_]*}. */
  public static boolean isIdentifier(String s) {
    if (s.isEmpty() || !(isAsciiLetter(s.charAt(0)) || s.charAt(0) == '_')) {
      return false;
    }
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!(isAsciiLetter(c) || c == '_' || (c >= '0' && c <= '9'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }
}
