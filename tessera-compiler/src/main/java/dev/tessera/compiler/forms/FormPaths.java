package dev.tessera.compiler.forms;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.runtime.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Form field paths ({@code lastName}, {@code address.city}, {@code items[i].name}) relative to the {@code t:object}
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
   * @param path Java code (a String) for the field path, e.g. {@code items[0].name}: the
   *     {@code name} attribute and the BindStatus path
   * @param id Java code (a String) for the element id: the path without '[' and ']', as in
   *     Spring and Thymeleaf ({@code items0.name})
   * @param value the field's current value, or null where the caller does not need it
   */
  public static final class FormField {
    private final String objectName;
    private final String path;
    private final String id;
    private final CompiledExpr value;

    public FormField(String objectName, String path, String id, CompiledExpr value) {
      this.objectName = objectName;
      this.path = path;
      this.id = id;
      this.value = value;
    }

    public String objectName() {
      return objectName;
    }

    public String path() {
      return path;
    }

    public String id() {
      return id;
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
          && Objects.equals(id, that.id)
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(objectName);
      h = 31 * h + Objects.hashCode(path);
      h = 31 * h + Objects.hashCode(id);
      h = 31 * h + Objects.hashCode(value);
      return h;
    }

    @Override
    public String toString() {
      return "FormField[objectName=" + objectName + ", path=" + path + ", id=" + id + ", value=" + value + "]";
    }

    /** Java code for {@code ctx.forms().<method>(objectName, path)}, e.g. {@code errors}. */
    public String formsCall(String method) {
      return "ctx.forms()." + method + "(" + objectName + ", " + path + ")";
    }
  
  }

  /** A field given as a literal {@code path} relative to {@code selection}. */
  public static FormField literal(Scope.Selection selection, String path, CompiledExpr value) {
    return new FormField(
        JavaStrings.literal(selection.objectName()), JavaStrings.literal(path), JavaStrings.literal(idOf(path)), value);
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
    return new FormField(
        j + ".objectName()",
        j + ".path()",
        j + ".path().replace(\"[\", \"\").replace(\"]\", \"\")",
        new CompiledExpr(j + ".value()", object, pos));
  }

  /** Whether {@code t} is the runtime {@code Field} type. */
  public static boolean isField(TypeMirror t) {
    return t != null && ExpressionCompiler.isDeclared(t, FIELD);
  }

  /**
   * Resolves a field path expression against the {@code t:object} selection in {@code scope}:
   * {@code lastName}, {@code *{address.city}}, or with index segments {@code *{items[0].name}},
   * {@code *{items[i].name}}, {@code *{items[${iterStat.index}].name}}. An index is an int literal
   * or an int-typed variable or property chain (a loop variable, {@code iterStat.index}), and the
   * indexed property must be a List or an array; the path string is built from the literal names
   * and the int, so it never contains user-controlled text. Reports through
   * {@code expressions}' diagnostics and returns null on error. The caller checks that there is a
   * selection and that {@code e} {@link #isPath is a path}.
   *
   * @param withValue compile the field's current value (and check the property types); without
   *     it only the index expressions are compiled and the value is null
   */
  public static FormField resolve(Expr e, Scope scope, ExpressionCompiler expressions, boolean withValue) {
    Scope.Selection selection = scope.selection().get();
    DiagnosticSink diagnostics = expressions.env().diagnostics();
    List<Expr> chain = new ArrayList<>();
    for (Expr step = e; step != null; step = target(step)) {
      chain.add(0, step);
    }
    if (!isPath(e)) {
      throw new IllegalArgumentException("not a field path: " + e);
    }
    Expr root = chain.get(0);
    String rootName = root instanceof Expr.Var ? ((Expr.Var) root).name() : ((Expr.SelectionVar) root).name();
    // name and id as Java string concatenations: text parts are Java literals, index parts ints.
    List<String> name = new ArrayList<>();
    List<String> id = new ArrayList<>();
    StringBuilder text = new StringBuilder(rootName);
    Expr value = new Expr.SelectionVar(rootName, root.pos());
    CompiledExpr current = withValue ? expressions.compile(value, scope) : null;
    if (withValue && current == null) {
      return null;
    }
    for (Expr step : chain.subList(1, chain.size())) {
      if (step instanceof Expr.Property) {
        String property = ((Expr.Property) step).name();
        text.append('.').append(property);
        value = new Expr.Property(value, property, true, step.pos());
      } else {
        Expr.Index index = (Expr.Index) step;
        if (withValue && !isListOrArray(current.type(), expressions)) {
          // TODO(#190): Map keys (Spring's map[key]) need a safe key encoding; only ints for now.
          diagnostics.error(
              step.pos(),
              "cannot index " + current.type() + " in a field path: [...] works on a List or an array");
          return null;
        }
        String code = index(index.index(), scope, expressions);
        if (code == null) {
          return null;
        }
        text.append('[');
        if (index.index() instanceof Expr.Literal) {
          text.append(code);
        } else {
          flush(text, name, id);
          name.add(code);
          id.add(code);
        }
        text.append(']');
        value = new Expr.Index(value, index.index(), step.pos());
      }
      if (withValue) {
        current = expressions.compile(value, scope);
        if (current == null) {
          return null;
        }
      }
    }
    flush(text, name, id);
    return new FormField(JavaStrings.literal(selection.objectName()), concat(name), concat(id), current);
  }

  /**
   * The Java code (an int, as a decimal literal if constant) of a field path index, or null after
   * reporting: an int literal, or an int variable or property chain.
   */
  private static String index(Expr e, Scope scope, ExpressionCompiler expressions) {
    if (!(e instanceof Expr.Literal) && !isVariableChain(e)) {
      expressions.env().diagnostics().error(e.pos(), "a field path index must be an int literal or an int variable such as i or"
          + " iterStat.index, not an expression");
      return null;
    }
    CompiledExpr c = expressions.compile(e, scope);
    if (c == null) {
      return null;
    }
    TypeMirror t = c.type();
    boolean isInt =
        t.getKind() == TypeKind.INT
            || t.getKind() == TypeKind.SHORT
            || t.getKind() == TypeKind.BYTE
            || ExpressionCompiler.isDeclared(t, "java.lang.Integer")
            || ExpressionCompiler.isDeclared(t, "java.lang.Short")
            || ExpressionCompiler.isDeclared(t, "java.lang.Byte");
    if (!isInt) {
      expressions.env().diagnostics().error(e.pos(), "a field path index must be an int, got " + t);
      return null;
    }
    if (e instanceof Expr.Literal) {
      return String.valueOf(((Expr.Literal) e).value());
    }
    return "((int) " + c.code() + ")";
  }

  private static boolean isVariableChain(Expr e) {
    if (e instanceof Expr.Var) {
      return true;
    }
    return e instanceof Expr.Property && !((Expr.Property) e).nullSafe() && isVariableChain(((Expr.Property) e).target());
  }

  /**
   * Whether {@code e} has the shape of a field path: a name ({@code x} or {@code *{x}}) followed by
   * {@code .name} and {@code [index]} steps (not null-safe ones). Indexes are checked by
   * {@link #resolve}.
   */
  public static boolean isPath(Expr e) {
    Expr root = root(e);
    return root instanceof Expr.SelectionVar
        || (root instanceof Expr.Var && !((Expr.Var) root).name().contains("$"));
  }

  /** The first step of the path {@code e}: {@code x} in {@code x.y[i].z}. */
  public static Expr root(Expr e) {
    while (target(e) != null) {
      e = target(e);
    }
    return e;
  }

  /** The step before {@code e} in a field path, or null at the root (or for a non-path). */
  private static Expr target(Expr e) {
    if (e instanceof Expr.Property && !((Expr.Property) e).nullSafe()) {
      return ((Expr.Property) e).target();
    }
    return e instanceof Expr.Index ? ((Expr.Index) e).target() : null;
  }

  private static boolean isListOrArray(TypeMirror t, ExpressionCompiler expressions) {
    if (t.getKind() == TypeKind.ARRAY) {
      return true;
    }
    Types types = expressions.env().types();
    TypeMirror list = types.erasure(expressions.env().elements().getTypeElement("java.util.List").asType());
    return t.getKind() == TypeKind.DECLARED && types.isSubtype(types.erasure(t), list);
  }

  /** Moves the pending path text to {@code name} and (without brackets, as Spring) to {@code id}. */
  private static void flush(StringBuilder text, List<String> name, List<String> id) {
    if (text.length() > 0) {
      name.add(JavaStrings.literal(text.toString()));
      String noBrackets = idOf(text.toString());
      if (!noBrackets.isEmpty()) {
        id.add(JavaStrings.literal(noBrackets));
      }
      text.setLength(0);
    }
  }

  private static String concat(List<String> parts) {
    if (parts.size() == 1) {
      return parts.get(0);
    }
    return "(" + String.join(" + ", parts) + ")";
  }

  /** Spring's and Thymeleaf's element id for a field name: the name without '[' and ']'. */
  static String idOf(String path) {
    return path.replace("[", "").replace("]", "");
  }

  /**
   * Parses a string-literal field path ({@code lastName}, {@code address.city},
   * {@code items[0].name}) into the expression {@link #resolve} takes, or returns null if
   * {@code path} is not one. Indexes are decimal ints without leading zeros.
   */
  static Expr parseLiteral(String path, SourcePos pos) {
    Expr e = null;
    for (String segment : path.split("\\.", -1)) {
      int bracket = segment.indexOf('[');
      String head = bracket < 0 ? segment : segment.substring(0, bracket);
      if (!isIdentifier(head)) {
        return null;
      }
      e = e == null ? new Expr.SelectionVar(head, pos) : new Expr.Property(e, head, false, pos);
      String rest = bracket < 0 ? "" : segment.substring(bracket);
      while (!rest.isEmpty()) {
        int close = rest.indexOf(']');
        if (!rest.startsWith("[") || close < 0 || !rest.substring(1, close).matches("0|[1-9][0-9]{0,8}")) {
          return null;
        }
        e = new Expr.Index(e, new Expr.Literal(Integer.valueOf(rest.substring(1, close)), pos), pos);
        rest = rest.substring(close + 1);
      }
    }
    return e;
  }

  /**
   * Compiles the string-literal {@code path} ({@code items[0].name} allowed) against the selection
   * in {@code scope} to the field's current value. Steps after the first are null-safe (a null
   * intermediate object yields null). Reports and returns null on error (no selection, unknown
   * property, bad path).
   */
  public static CompiledExpr compile(String path, SourcePos pos, Scope scope, ExpressionCompiler expressions) {
    if (!scope.selection().isPresent()) {
      expressions.env().diagnostics().error(pos, "field '" + path + "' can only be used inside t:object: put t:object=\"...\" on"
              + " this element or an enclosing one");
      return null;
    }
    Expr e = parseLiteral(path, pos);
    if (e == null) {
      expressions.env().diagnostics().error(pos, "'" + path + "' is not a field path: use names like lastName, address.city or"
          + " items[0].name");
      return null;
    }
    FormField field = resolve(e, scope, expressions, true);
    return field == null ? null : field.value();
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
