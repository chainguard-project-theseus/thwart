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
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Form field paths ({@code lastName}, {@code address.city}, {@code items[i].name},
 * {@code prices[key]}) relative to the {@code t:object} selection. Paths are checked at build time against the selection type, so an unknown field
 * is a build error naming the type.
 *
 * <p>Inside a fragment, a parameter of type {@code dev.tessera.runtime.Field} stands for a path
 * the caller checked against its own {@code t:object}; {@link FormField} covers both forms.
 */
public final class FormPaths {
  private FormPaths() {}

  /** Path meaning "all errors of the form object" for {@code t:errors} and {@code fields.*}. */
  public static final String ALL = "*";

  /**
   * Path meaning the form object's own (global, object-level) errors, not a field's, for {@code
   * t:errors} and {@code fields.*}: Spring's BindStatus of the object name, as in Thymeleaf.
   */
  public static final String GLOBAL = "global";

  /** What resolving a field path checks and compiles. */
  public enum Check {
    /** Nothing: only the path's index expressions are compiled, and the value is null. */
    NONE,
    /**
     * The path, but not the field's value, which is null: for a field bound by name only, whose
     * value is never read ({@code t:errors}, {@code fields.*}, {@code t:field} on a file input), so
     * its type need not be view-safe ({@link ExpressionCompiler#pathType}).
     */
    PATH,
    /** The path and the field's current value, whose type must not be on the deny-list. */
    VALUE
  }

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

  /**
   * The {@code Field} variable at the root of {@code e} if {@code e} is a sub-path of it,
   * {@code name.answer} or {@code name.items[i].text}, and empty otherwise (also for a bare
   * {@code name}, see {@link #fieldVariable}).
   */
  public static Optional<Variable> fieldRoot(Expr e, Scope scope) {
    Expr root = root(e);
    return root != e && isPath(e) ? fieldVariable(root, scope) : Optional.empty();
  }

  /** {@code T} of {@code Field<T>}, or null for {@code Field<?>} (a plain {@code Field}). */
  public static TypeMirror fieldType(TypeMirror field) {
    List<? extends TypeMirror> args = ((DeclaredType) field).getTypeArguments();
    return args.size() == 1 && args.get(0).getKind() != TypeKind.WILDCARD ? args.get(0) : null;
  }

  /**
   * A sub-path of the {@code Field<T>} variable {@code field} ({@link #fieldRoot}):
   * {@code name.answer} binds {@code <name's path>.answer}, checked against {@code T}. The path
   * appended to the caller's is built from the literal names and int indexes only, as in
   * {@link #resolve}. Reports and returns null on error, including for a plain {@code Field},
   * whose type is not known.
   */
  public static FormField resolveSubPath(
      Expr e, Variable field, Scope scope, ExpressionCompiler expressions, Check check) {
    Expr root = root(e);
    TypeMirror type = fieldType(field.type());
    if (type == null) {
      expressions.env().diagnostics().error(root.pos(), "'" + field.name() + "' is a Field of unknown type, so its properties"
          + " cannot be bound: declare its type in the fragment signature, e.g. Field<Criterion> " + field.name());
      return null;
    }
    String j = field.javaName();
    List<String> name = new ArrayList<>();
    name.add(j + ".path()");
    List<String> id = new ArrayList<>();
    id.add(j + ".id()");
    // The value chain starts at the field's value, seen under the field's own name.
    Scope valueScope = scope.with(new Variable(field.name(), type, j + ".value()"));
    return steps(
        j + ".objectName()", name, id, new StringBuilder(), root, valueScope, e, scope, expressions, check);
  }

  /**
   * A field held in a {@code Field<T>} variable; its value has static type {@code T}, or
   * {@code Object} for a plain {@code Field}.
   */
  public static FormField fromVariable(Variable v, SourcePos pos, ExpressionCompiler expressions) {
    TypeMirror type = fieldType(v.type());
    TypeMirror object =
        type != null ? type : expressions.env().elements().getTypeElement("java.lang.Object").asType();
    String j = v.javaName();
    return new FormField(
        j + ".objectName()",
        j + ".path()",
        j + ".id()",
        new CompiledExpr(j + ".value()", object, pos));
  }

  /** Whether {@code t} is the runtime {@code Field} type. */
  public static boolean isField(TypeMirror t) {
    return t != null && ExpressionCompiler.isDeclared(t, FIELD);
  }

  /**
   * Resolves a field path expression against the {@code t:object} selection in {@code scope}:
   * {@code lastName}, {@code *{address.city}}, or with index segments {@code *{items[0].name}},
   * {@code *{items[i].name}}, {@code *{items[${iterStat.index}].name}}, or with map key segments
   * {@code *{prices[key]}}, {@code *{prices['x']}}. A List or array index is an int literal
   * or an int-typed variable or property chain (a loop variable, {@code iterStat.index}); a Map key
   * is a literal or a variable or property chain of type int, long, String or an enum, written as
   * Spring's {@code BeanWrapper} reads it, unquoted: {@code prices[x]}. A String key is checked by
   * {@link Forms#pathKey} (at build time if literal), so it cannot change the path's structure.
   * Reports through
   * {@code expressions}' diagnostics and returns null on error. The caller checks that there is a
   * selection and that {@code e} {@link #isPath is a path}.
   *
   * @param check what to check and compile: with {@link Check#VALUE} the field's current value
   */
  public static FormField resolve(Expr e, Scope scope, ExpressionCompiler expressions, Check check) {
    if (!isPath(e)) {
      throw new IllegalArgumentException("not a field path: " + e);
    }
    Scope.Selection selection = scope.selection().get();
    Expr root = root(e);
    String rootName = root instanceof Expr.Var ? ((Expr.Var) root).name() : ((Expr.SelectionVar) root).name();
    return steps(
        JavaStrings.literal(selection.objectName()),
        new ArrayList<>(),
        new ArrayList<>(),
        new StringBuilder(rootName),
        new Expr.SelectionVar(rootName, root.pos()),
        scope,
        e,
        scope,
        expressions,
        check);
  }

  /**
   * Builds the field for the steps of the path {@code e} after its root.
   *
   * @param name Java string parts of the name so far; {@code id} likewise for the id
   * @param text pending literal path text, not yet in {@code name}/{@code id}
   * @param value the root's value, compiled in {@code valueScope}; {@code scope} compiles indexes
   */
  private static FormField steps(
      String objectName,
      List<String> name,
      List<String> id,
      StringBuilder text,
      Expr value,
      Scope valueScope,
      Expr e,
      Scope scope,
      ExpressionCompiler expressions,
      Check check) {
    DiagnosticSink diagnostics = expressions.env().diagnostics();
    List<Expr> chain = new ArrayList<>();
    for (Expr step = e; step != null; step = target(step)) {
      chain.add(0, step);
    }
    CompiledExpr current = check == Check.VALUE ? expressions.compile(value, valueScope) : null;
    TypeMirror type = type(current, value, valueScope, expressions, check);
    if (check != Check.NONE && type == null) {
      return null;
    }
    for (Expr step : chain.subList(1, chain.size())) {
      if (step instanceof Expr.Property) {
        String property = ((Expr.Property) step).name();
        text.append('.').append(property);
        value = new Expr.Property(value, property, true, step.pos());
      } else {
        Expr.Index index = (Expr.Index) step;
        boolean map = check != Check.NONE && isMap(type, expressions);
        if (check != Check.NONE && !map && !isListOrArray(type, expressions)) {
          diagnostics.error(
              step.pos(),
              "cannot index " + type + " in a field path: [...] works on a List, an array or a Map");
          return null;
        }
        // Without a check the container is unknown: allow both (t:field reports).
        String code = index(index.index(), scope, expressions, map || check == Check.NONE);
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
      if (check == Check.VALUE) {
        current = expressions.compile(value, valueScope);
      }
      type = type(current, value, valueScope, expressions, check);
      if (check != Check.NONE && type == null) {
        return null;
      }
    }
    flush(text, name, id);
    return new FormField(objectName, concat(name), concat(id), current);
  }

  /** The type of the path so far: {@code current}'s for VALUE, resolved without reading it for PATH. */
  private static TypeMirror type(
      CompiledExpr current, Expr value, Scope valueScope, ExpressionCompiler expressions, Check check) {
    if (check == Check.VALUE) {
      return current == null ? null : current.type();
    }
    return check == Check.PATH ? expressions.pathType(value, valueScope) : null;
  }

  /**
   * A field path index or map key, or null after reporting: for a literal, the path text (an int
   * as a decimal, a String key checked by {@link Forms#isPathKey}); otherwise Java code for an int
   * or a {@code String}. An index is an int literal or an int variable or property chain; with
   * {@code mapKey}, a key may also be a long, a String or an enum (its name, as Spring converts it
   * back), and a variable String key is checked by {@link Forms#pathKey} when the page renders.
   */
  private static String index(Expr e, Scope scope, ExpressionCompiler expressions, boolean mapKey) {
    DiagnosticSink diagnostics = expressions.env().diagnostics();
    if (!(e instanceof Expr.Literal) && !isVariableChain(e)) {
      diagnostics.error(e.pos(), mapKey
          ? "a map key in a field path must be a literal or a variable such as key or entry.key, not an expression"
          : "a field path index must be an int literal or an int variable such as i or iterStat.index, not an expression");
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
    boolean isLong = t.getKind() == TypeKind.LONG || ExpressionCompiler.isDeclared(t, "java.lang.Long");
    boolean isString = ExpressionCompiler.isDeclared(t, "java.lang.String");
    boolean isEnum = t.getKind() == TypeKind.DECLARED && expressions.isSubtypeOf(t, "java.lang.Enum");
    if (!isInt && !(mapKey && (isLong || isString || isEnum))) {
      diagnostics.error(e.pos(), mapKey
          ? "a map key in a field path must be an int, a long, a String or an enum, got " + t
          : "a field path index must be an int, got " + t);
      return null;
    }
    if (e instanceof Expr.Literal) {
      String text = String.valueOf(((Expr.Literal) e).value());
      if (isString && !Forms.isPathKey(text)) {
        diagnostics.error(e.pos(), "map key '" + text + "' cannot be bound in a field path: a key must be non-empty and"
            + " contain no '[', ']' or quotes, which Spring's property paths cannot escape");
        return null;
      }
      return text;
    }
    if (isString) {
      return Forms.class.getName() + ".pathKey(" + c.code() + ")";
    }
    if (isEnum) {
      return Forms.class.getName() + ".pathKey(" + Values.class.getName() + ".enumName(" + c.code() + "))";
    }
    return (isLong ? "((long) " : "((int) ") + c.code() + ")";
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

  private static boolean isMap(TypeMirror t, ExpressionCompiler expressions) {
    return t.getKind() == TypeKind.DECLARED && expressions.isSubtypeOf(t, "java.util.Map");
  }

  private static boolean isListOrArray(TypeMirror t, ExpressionCompiler expressions) {
    return t.getKind() == TypeKind.ARRAY
        || (t.getKind() == TypeKind.DECLARED && expressions.isSubtypeOf(t, "java.util.List"));
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
   * {@code items[0].name}, {@code prices[x]}) into the expression {@link #resolve} takes, or returns
   * null if {@code path} is not one. Indexes are decimal ints without leading zeros; a map key is
   * an identifier, unquoted as Spring writes it.
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
        if (!rest.startsWith("[") || close < 0) {
          return null;
        }
        String key = rest.substring(1, close);
        if (key.matches("0|[1-9][0-9]{0,8}")) {
          e = new Expr.Index(e, new Expr.Literal(Integer.valueOf(key), pos), pos);
        } else if (isIdentifier(key)) {
          e = new Expr.Index(e, new Expr.Literal(key, pos), pos);
        } else {
          return null;
        }
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
    FormField field = literalField(path, pos, scope, expressions, Check.VALUE);
    return field == null ? null : field.value();
  }

  /**
   * Resolves the string-literal {@code path} against the selection in {@code scope}, as
   * {@link #compile} but with {@code check} ({@link Check#PATH}: the value is not compiled and is
   * null). Reports and returns null on error.
   */
  public static FormField literalField(
      String path, SourcePos pos, Scope scope, ExpressionCompiler expressions, Check check) {
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
    return resolve(e, scope, expressions, check);
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
