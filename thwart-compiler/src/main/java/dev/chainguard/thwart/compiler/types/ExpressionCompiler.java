package dev.chainguard.thwart.compiler.types;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.CompilerEnv;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.expr.TypeRef;
import dev.chainguard.thwart.compiler.forms.FieldsCompiler;
import dev.chainguard.thwart.compiler.forms.FormPaths;
import dev.chainguard.thwart.compiler.forms.IdsCompiler;
import dev.chainguard.thwart.compiler.links.LinkCompiler;
import dev.chainguard.thwart.compiler.messages.MessageCompiler;
import dev.chainguard.thwart.runtime.Fragment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Resolves and type-checks expressions against javac's model and emits Java code for them.
 * This is where principles 2 and 3 are enforced:
 *
 * <ul>
 *   <li>identifiers resolve only to {@link Scope} variables or registered function namespaces;
 *   <li>{@code a.b} / {@code a.b()} / {@code a.getB()} resolve only to a record component or a
 *       public no-arg {@code getB()}/{@code isB()} on a view-safe type ({@link ViewSafety}), or
 *       to a collection/String built-in; any other method call is an error;
 *   <li>{@code ns.fn(args)} resolves only through {@link FunctionRegistry};
 *   <li>never-view-safe types are rejected wherever they appear (params, results, arguments).
 * </ul>
 *
 * Messages and links are delegated to {@code MessageCompiler} / {@code LinkCompiler}, and the
 * reserved {@code fields} and {@code ids} namespaces to {@code FieldsCompiler} / {@code IdsCompiler}. All
 * methods report errors with the expression's position and return {@code null} on failure.
 *
 * <p>Emitted code is always a Java primary (parenthesized where needed), so callers may append
 * {@code .x()} or use it as an argument directly. Helpers live in {@code dev.chainguard.thwart.runtime.internal.Values}.
 */
public final class ExpressionCompiler {
  private static final String VALUES = "dev.chainguard.thwart.runtime.internal.Values";
  private static final String FRAGMENT = Fragment.class.getName();
  private static final String BIG_DECIMAL = "java.math.BigDecimal";
  private static final Set<String> OBJECT_METHODS =
      Backports.setOf("getClass", "hashCode", "equals", "toString", "wait", "notify", "notifyAll", "clone", "finalize");

  private final CompilerEnv env;
  private final ViewSafety safety;
  private final TypeResolver resolver;
  private FunctionRegistry functions;
  private int lambdas;
  /** Set while {@link #pathType} resolves a form path whose value is never read. */
  private boolean pathOnly;

  public ExpressionCompiler(CompilerEnv env) {
    this.env = env;
    this.safety = new ViewSafety(env);
    this.resolver = new TypeResolver(env, safety);
  }

  public CompilerEnv env() {
    return env;
  }

  /**
   * The function namespaces visible to templates. Built (and its registration errors reported)
   * on first use, i.e. as soon as any expression or parameter type is compiled.
   */
  FunctionRegistry functions() {
    if (functions == null) {
      functions = new FunctionRegistry(env);
    }
    return functions;
  }

  /**
   * The static type of the form field path {@code e} (property and index steps from a variable or a
   * {@code *{...}} selection) for a field that is bound by name only and whose value is never read:
   * {@code t:errors}, {@code fields.*}, {@code t:field} on a file input. The steps resolve
   * as in {@link #compile}, so a property is still only resolved on a view-safe type, but a step's
   * own type may be one that is never view-safe, such as {@code MultipartFile} or
   * {@code List<MultipartFile>}. Only the type is returned, never code, so nothing can read the
   * value. Reports and returns null on error.
   */
  public TypeMirror pathType(Expr e, Scope scope) {
    boolean was = pathOnly;
    pathOnly = true;
    try {
      CompiledExpr c = compile(e, scope);
      return c == null ? null : c.type();
    } finally {
      pathOnly = was;
    }
  }

  /** Compiles {@code e} and checks its value may be output as text; reports and returns null on error. */
  public CompiledExpr compileOutput(Expr e, Scope scope) {
    CompiledExpr c = compile(e, scope);
    return c != null && checkOutputType(c.type(), e.pos(), true) ? c : null;
  }

  /**
   * As {@link #compileOutput}, but any view-safe value is allowed, such as a record: for JSON,
   * which writes a record's components instead of calling its {@code toString()}.
   */
  public CompiledExpr compileJsonOutput(Expr e, Scope scope) {
    CompiledExpr c = compile(e, scope);
    return c != null && checkOutputType(c.type(), e.pos(), false) ? c : null;
  }

  /** Compiles a value expression. */
  public CompiledExpr compile(Expr expr, Scope scope) {
    functions();
    if (expr instanceof Expr.Literal) {
      return literal((Expr.Literal) expr);
    } else if (expr instanceof Expr.Var) {
      return variable((Expr.Var) expr, scope);
    } else if (expr instanceof Expr.SelectionVar) {
      return selection((Expr.SelectionVar) expr, scope);
    } else if (expr instanceof Expr.Property) {
      return property((Expr.Property) expr, scope);
    } else if (expr instanceof Expr.MethodCall) {
      return methodCall((Expr.MethodCall) expr, scope);
    } else if (expr instanceof Expr.Index) {
      return index((Expr.Index) expr, scope);
    } else if (expr instanceof Expr.FunctionCall) {
      Expr.FunctionCall f = (Expr.FunctionCall) expr;
      return callNamespace(f.namespace(), f.name(), f.args(), f.pos(), scope);
    } else if (expr instanceof Expr.Unary) {
      return unary((Expr.Unary) expr, scope);
    } else if (expr instanceof Expr.Binary) {
      return binary((Expr.Binary) expr, scope);
    } else if (expr instanceof Expr.Conditional) {
      return conditional((Expr.Conditional) expr, scope);
    } else if (expr instanceof Expr.Elvis) {
      return elvis((Expr.Elvis) expr, scope);
    } else if (expr instanceof Expr.Message) {
      return MessageCompiler.compile((Expr.Message) expr, scope, this);
    } else if (expr instanceof Expr.Link) {
      return LinkCompiler.compile((Expr.Link) expr, scope, this);
    } else if (expr instanceof Expr.Interpolation) {
      return interpolation((Expr.Interpolation) expr, scope);
    } else if (expr instanceof Expr.FragmentExpr) {
      return error(
          expr.pos(), "a fragment expression ~{...} is only allowed in t:insert/t:replace or as a fragment argument");
    }
    throw new IllegalArgumentException("unknown expression " + expr);
  }

  /**
   * Compiles a condition to Java code of type {@code boolean}: {@code boolean} as is, Boolean
   * null-safe, anything else via {@code dev.chainguard.thwart.runtime.internal.Conditions.isTrue}.
   */
  public CompiledExpr compileCondition(Expr expr, Scope scope) {
    CompiledExpr c = compile(expr, scope);
    if (c == null) {
      return null;
    }
    TypeMirror t = c.type();
    String code;
    if (t.getKind() == TypeKind.BOOLEAN) {
      code = c.code();
    } else if (t.getKind() == TypeKind.NULL) {
      code = "false";
    } else if (isDeclared(t, "java.lang.Boolean")) {
      code = "java.lang.Boolean.TRUE.equals(" + c.code() + ")";
    } else {
      code = "dev.chainguard.thwart.runtime.internal.Conditions.isTrue(" + c.code() + ")";
    }
    return new CompiledExpr(code, prim(TypeKind.BOOLEAN), expr.pos());
  }

  /**
   * Element type for {@code t:each} over {@code iterable}: {@code T} for {@code Iterable<T>}
   * and {@code T[]}, {@code Map.Entry<K,V>} for {@code Map<K,V>}. Reports and returns null
   * otherwise.
   */
  public TypeMirror elementType(CompiledExpr iterable) {
    if (iterable == null) {
      return null;
    }
    TypeMirror e = iterableElement(iterable);
    if (e == null) {
      return null;
    }
    // A subtype such as `class Threads extends ArrayList<Thread>` passes the deny-list itself.
    Optional<String> bad = safety.neverViewSafeReason(e);
    if (bad.isPresent()) {
      return error(iterable.pos(), "cannot iterate over " + describe(iterable.type()) + ": " + bad.get());
    }
    return e;
  }

  private TypeMirror iterableElement(CompiledExpr iterable) {
    TypeMirror t = normalize(iterable.type());
    if (t.getKind() == TypeKind.ARRAY) {
      return normalize(((ArrayType) t).getComponentType());
    }
    if (t.getKind() == TypeKind.DECLARED) {
      if (isSubtypeOf(t, "java.util.Map")) {
        TypeMirror k = typeArg(t, "java.util.Map", 0);
        TypeMirror v = typeArg(t, "java.util.Map", 1);
        if (k == null || v == null) {
          return error(iterable.pos(), "cannot iterate over raw type " + t + ": declare its type arguments");
        }
        return types().getDeclaredType(elements().getTypeElement("java.util.Map.Entry"), k, v);
      }
      if (isSubtypeOf(t, "java.lang.Iterable")) {
        TypeMirror e = typeArg(t, "java.lang.Iterable", 0);
        if (e == null) {
          return error(iterable.pos(), "cannot iterate over raw type " + t + ": declare its type arguments");
        }
        return e;
      }
    }
    return error(iterable.pos(), "cannot iterate over " + describe(t) + ": t:each needs an Iterable, an array or a Map");
  }

  /**
   * Resolves a declared parameter type. Simple names resolve against {@code imports}
   * ({@code t:import} entries), then the implicit imports {@code java.lang}, {@code java.util},
   * {@code java.time}, {@code java.math}, {@code dev.chainguard.thwart.runtime}. Parameters must be
   * view-safe, {@code SafeHtml}, {@code Fragment} or {@code Field} (fragment
   * parameters only; {@code TemplateIndex} rejects it in {@code t:params}).
   */
  public TypeMirror resolveType(TypeRef ref, List<String> imports) {
    functions();
    TypeMirror t = resolver.resolve(ref, imports);
    if (t == null) {
      return null;
    }
    if (FormPaths.isField(t)) {
      // Field<T>: T is what the fragment may bind sub-paths of; a plain Field is Field<?>.
      TypeMirror fieldType = FormPaths.fieldType(t);
      if (fieldType != null && !safety.isViewSafe(fieldType)) {
        return error(ref.pos(), "parameter type " + t + " is not allowed: " + fieldType + " is not view-safe"
            + badTypeArgument(fieldType, ""));
      }
      return t;
    }
    Optional<String> never = safety.neverViewSafeReason(t);
    if (never.isPresent()) {
      return error(ref.pos(), "parameter type " + t + " is not allowed: " + never.get());
    }
    if (!safety.isViewSafe(t) && !isDeclared(t, FRAGMENT)) {
      return error(
          ref.pos(),
          "parameter type " + t + " is not view-safe" + badTypeArgument(t, ": use a record, an enum, a String, a"
              + " number or boolean, a java.time value, a UUID, a collection of those, or a class annotated"
              + " @TemplateView"));
    }
    return t;
  }

  /**
   * Checks that {@code type} may be output (view-safe or primitive, or a {@code Field}, which
   * outputs its path) and reports at {@code pos} if not. As {@code text}, it must also print as a
   * value ({@link ViewSafety#isText}), not a record's {@code toString()}.
   */
  boolean checkOutputType(TypeMirror type, SourcePos pos, boolean text) {
    if (type == null) {
      return false;
    }
    if (type.getKind() == TypeKind.VOID) {
      env.diagnostics().error(pos, "cannot output void: the expression has no value");
      return false;
    }
    Optional<String> never = safety.neverViewSafeReason(type);
    if (never.isPresent()) {
      env.diagnostics().error(pos, "cannot output a value of type " + type + ": " + never.get());
      return false;
    }
    if (!safety.isViewSafe(type) && !FormPaths.isField(type)) {
      env.diagnostics()
          .error(pos, "cannot output a value of type " + type + ": it is not view-safe" + badTypeArgument(type, ""));
      return false;
    }
    if (text && !safety.isText(type) && !FormPaths.isField(type)) {
      env.diagnostics()
          .error(pos, "cannot output a value of type " + type + " as text: the toString() of a record or"
              + " @TemplateView class prints its whole state. Output one of its properties instead, with ?. if"
              + " the value can be null (such as pet.type?.name); for a list or strings.join, print each"
              + " item's property in a t:each loop; or format it with a @TemplateFunction");
      return false;
    }
    return true;
  }

  /**
   * {@code type} seen as the container interface it implements if it is a {@code java.util}
   * implementation such as {@code ArrayList} or {@code TreeMap}, else {@code type}.
   */
  public TypeMirror containerView(TypeMirror type) {
    return safety.containerView(type);
  }

  /** Why {@code type} is never view-safe (the deny-list in {@link ViewSafety}), or empty. */
  public Optional<String> neverViewSafeReason(TypeMirror type) {
    return safety.neverViewSafeReason(type);
  }

  /**
   * Why generated code can't name a class in {@code type} (it, or a class it is nested in, is not
   * public; also as a type argument), or empty.
   */
  public Optional<String> inaccessibleReason(TypeMirror type) {
    String hidden = safety.inaccessible(type);
    return hidden == null ? Optional.empty() : Optional.of(ViewSafety.notPublic(hidden));
  }

  // ---------------------------------------------------------------- expressions

  private CompiledExpr literal(Expr.Literal l) {
    Object v = l.value();
    if (v == null) {
      return new CompiledExpr("null", types().getNullType(), l.pos());
    } else if (v instanceof String) {
      return new CompiledExpr(JavaStrings.literal((String) v), declared("java.lang.String"), l.pos());
    } else if (v instanceof Integer) {
      return new CompiledExpr(v.toString(), prim(TypeKind.INT), l.pos());
    } else if (v instanceof Long) {
      return new CompiledExpr(v + "L", prim(TypeKind.LONG), l.pos());
    } else if (v instanceof Double) {
      return new CompiledExpr(v.toString(), prim(TypeKind.DOUBLE), l.pos());
    } else if (v instanceof Boolean) {
      return new CompiledExpr(v.toString(), prim(TypeKind.BOOLEAN), l.pos());
    }
    throw new IllegalArgumentException("unknown literal " + v);
  }

  private CompiledExpr variable(Expr.Var v, Scope scope) {
    Optional<Variable> var = v.name().contains("$") ? Optional.empty() : scope.lookup(v.name());
    if (var.isPresent()) {
      return new CompiledExpr(var.get().javaName(), var.get().type(), v.pos());
    }
    if (isNamespace(v.name())) {
      return error(v.pos(), "'" + v.name() + "' is a function namespace, not a value: call a function, e.g. " + v.name() + ".fn(...)");
    }
    return error(v.pos(), "unknown identifier '" + v.name() + "': a template sees only its t:params and local variables");
  }

  private CompiledExpr selection(Expr.SelectionVar s, Scope scope) {
    Optional<Scope.Selection> sel = scope.selection();
    if (!sel.isPresent()) {
      return error(s.pos(), "*{" + s.name() + "} can only be used inside t:object: put t:object=\"...\" on this element or an"
          + " enclosing one, or write the full path (owner." + s.name() + "); a fragment doesn't see its caller's"
          + " t:object, so pass it the object as a parameter and put t:object on that, or, for a form control,"
          + " take a Field parameter");
    }
    return member(new CompiledExpr(sel.get().code(), sel.get().type(), s.pos()), s.name(), false, false, s.pos());
  }

  private CompiledExpr property(Expr.Property p, Scope scope) {
    if (p.target() instanceof Expr.Var && isNamespaceRef((Expr.Var) p.target(), scope)) {
      Expr.Var v = (Expr.Var) p.target();
      return error(p.pos(), "'" + v.name() + "." + p.name() + "' is a function: call it as " + v.name() + "." + p.name() + "(...)");
    }
    CompiledExpr target = compile(p.target(), scope);
    return target == null ? null : member(target, p.name(), p.nullSafe(), false, p.pos());
  }

  private CompiledExpr methodCall(Expr.MethodCall m, Scope scope) {
    if (m.target() instanceof Expr.Var && !scope.lookup(((Expr.Var) m.target()).name()).isPresent()) {
      Expr.Var v = (Expr.Var) m.target();
      if (isNamespace(v.name())) {
        return callNamespace(v.name(), m.name(), m.args(), m.pos(), scope);
      }
      return error(v.pos(), "unknown identifier or function namespace '" + v.name() + "': declare it in"
          + " t:params, or register a class annotated @TemplateFunctions(\"" + v.name() + "\")");
    }
    CompiledExpr target = compile(m.target(), scope);
    if (target == null) {
      return null;
    }
    if (OBJECT_METHODS.contains(m.name())) {
      return objectMethodError(m.name(), m.pos());
    }
    if (!m.args().isEmpty()) {
      return error(
          m.pos(),
          "method call '" + m.name() + "(...)' is not allowed: templates can read record components, getX()/isX()"
              + " getters and built-ins, and call @TemplateFunction functions");
    }
    return member(target, m.name(), m.nullSafe(), true, m.pos());
  }

  /**
   * {@code target.name}, {@code target.name()} or {@code target.getName()}; {@code call} is true
   * for the method-call forms.
   */
  private CompiledExpr member(CompiledExpr target, String name, boolean nullSafe, boolean call, SourcePos pos) {
    TypeMirror type = normalize(target.type());
    if (type.getKind() == TypeKind.NULL) {
      return error(pos, "cannot read '" + name + "' of null: the expression is the literal null");
    }
    if (type.getKind().isPrimitive()) {
      return error(pos, "cannot read '" + name + "' of a " + type + " value: primitives have no properties");
    }
    if (OBJECT_METHODS.contains(name) || ("get" + JavaStrings.capitalize(name)).equals("getClass")) {
      return objectMethodError(name, pos);
    }
    Optional<String> never = safety.neverViewSafeReason(type);
    if (never.isPresent()) {
      return error(pos, "cannot read '" + name + "': " + never.get());
    }
    String prefix = "";
    String suffix;
    TypeMirror result;
    Builtin builtin = builtin(type, name);
    if (builtin != null) {
      prefix = builtin.prefix();
      suffix = builtin.suffix();
      result = builtin.type();
      if (result == null) {
        return error(pos, "cannot read '" + name + "' of raw type " + type + ": declare its type arguments");
      }
    } else {
      if (!safety.isViewSafe(type)) {
        return error(
            pos,
            "cannot read '" + name + "': " + describe(type) + " is not view-safe"
                + badTypeArgument(type, " (make it a record or annotate it @TemplateView)"));
      }
      // An ArrayList or TreeMap is read as its List or Map: only the interface's getters.
      TypeMirror owner = safety.containerView(type);
      ExecutableElement accessor = owner.getKind() == TypeKind.DECLARED ? findAccessor((DeclaredType) owner, name, call) : null;
      if (accessor == null) {
        return error(
            pos,
            "'" + name + "' is not a property of " + type + ": templates can read record components, public"
                + " getX()/isX() getters and built-ins (size, isEmpty, length, first, last)"
                + (isSubtypeOf(type, "java.util.Map") ? "; read a map value by key as m['" + name + "']" : "")
                + (isSubtypeOf(type, "java.util.Optional") ? "; unwrap the Optional in the view type (orElse(null))" : ""));
      }
      ExecutableType et = (ExecutableType) types().asMemberOf((DeclaredType) owner, accessor);
      suffix = "." + accessor.getSimpleName() + "()";
      result = normalize(et.getReturnType());
    }
    Optional<String> bad = safety.neverViewSafeReason(result);
    if (bad.isPresent() && !pathOnly) {
      return error(pos, "cannot read '" + name + "' of " + type + ": it returns " + result + ", and " + bad.get());
    }
    if (!pathOnly && inaccessibleResult(result, "'" + name + "' of " + type, pos)) {
      return null;
    }
    if (!nullSafe) {
      return new CompiledExpr(prefix + target.code() + suffix, result, pos);
    }
    TypeMirror boxed = box(result);
    String p = "v$$" + (++lambdas);
    String code = "((" + JavaStrings.typeName(boxed) + ") " + VALUES + ".nav(" + target.code() + ", " + p + " -> " + prefix + p + suffix + "))";
    return new CompiledExpr(code, boxed, pos);
  }

  /**
   * A built-in member: Java code to put around the receiver and its type (null for a raw List
   * element).
   */
  private static final class Builtin {
    private final String prefix;
    private final String suffix;
    private final TypeMirror type;

    private Builtin(String prefix, String suffix, TypeMirror type) {
      this.prefix = prefix;
      this.suffix = suffix;
      this.type = type;
    }

    public String prefix() {
      return prefix;
    }

    public String suffix() {
      return suffix;
    }

    public TypeMirror type() {
      return type;
    }

    Builtin(String suffix, TypeMirror type) {
      this("", suffix, type);
    }
  }

  private Builtin builtin(TypeMirror type, String name) {
    if (type.getKind() == TypeKind.ARRAY) {
      return name.equals("length") ? new Builtin(".length", prim(TypeKind.INT)) : null;
    }
    if (type.getKind() != TypeKind.DECLARED) {
      return null;
    }
    boolean isString = isDeclared(type, "java.lang.String");
    boolean sized = isSubtypeOf(type, "java.util.Collection") || isSubtypeOf(type, "java.util.Map");
    if (isString && name.equals("length")) {
      return new Builtin(".length()", prim(TypeKind.INT));
    }
    if (sized && name.equals("size")) {
      return new Builtin(".size()", prim(TypeKind.INT));
    }
    if ((isString || sized) && (name.equals("isEmpty") || name.equals("empty"))) {
      return new Builtin(".isEmpty()", prim(TypeKind.BOOLEAN));
    }
    if (isSubtypeOf(type, "java.util.List")) {
      if (name.equals("first") || name.equals("getFirst")) {
        return new Builtin(VALUES + ".first(", ")", typeArg(type, "java.util.List", 0));
      }
      if (name.equals("last") || name.equals("getLast")) {
        return new Builtin(VALUES + ".last(", ")", typeArg(type, "java.util.List", 0));
      }
    }
    return null;
  }

  /**
   * A record component accessor or public no-arg getter for {@code name}, or null. The property
   * {@code x.isActive} (not the call {@code x.isActive()}) also tries {@code getIsActive()}, first,
   * as SpEL, OGNL and JavaBeans do: Lombok generates it for a {@code Boolean isActive} field.
   */
  private ExecutableElement findAccessor(DeclaredType type, String name, boolean call) {
    TypeElement te = (TypeElement) type.asElement();
    Set<String> components = new HashSet<>();
    if (ViewSafety.isRecord(te)) {
      for (Element e : te.getEnclosedElements()) {
        if (e.getKind().name().equals("RECORD_COMPONENT")) {
          components.add(e.getSimpleName().toString());
        }
      }
    }
    List<String> candidates = new ArrayList<>();
    if (components.contains(name)) {
      candidates.add(name);
    }
    if (isGetterName(name)) {
      if (!call && name.startsWith("is")) {
        candidates.add("get" + JavaStrings.capitalize(name));
      }
      candidates.add(name);
      String prop = JavaStrings.decapitalize(name.substring(name.startsWith("get") ? 3 : 2));
      if (components.contains(prop)) {
        candidates.add(prop);
      }
    } else {
      candidates.add("get" + JavaStrings.capitalize(name));
      candidates.add("is" + JavaStrings.capitalize(name));
    }
    List<ExecutableElement> methods = ElementFilter.methodsIn(elements().getAllMembers(te));
    for (String c : candidates) {
      for (ExecutableElement m : methods) {
        if (m.getSimpleName().contentEquals(c)
            && m.getModifiers().contains(Modifier.PUBLIC)
            && !m.getModifiers().contains(Modifier.STATIC)
            && m.getParameters().isEmpty()
            && m.getTypeParameters().isEmpty()
            && m.getReturnType().getKind() != TypeKind.VOID
            && !((TypeElement) m.getEnclosingElement()).getQualifiedName().contentEquals(ViewSafety.OBJECT)) {
          return m;
        }
      }
    }
    return null;
  }

  private CompiledExpr index(Expr.Index i, Scope scope) {
    CompiledExpr target = compile(i.target(), scope);
    CompiledExpr idx = compile(i.index(), scope);
    if (target == null || idx == null) {
      return null;
    }
    TypeMirror t = normalize(target.type());
    // A null-safe index (a form field path step, #971) reads the receiver once, through Values.nav.
    String recv = i.nullSafe() ? "v$$" + (++lambdas) : target.code();
    String code;
    TypeMirror result;
    if (t.getKind() == TypeKind.ARRAY) {
      if (!isIntIndexBoxed(idx.type())) {
        return error(i.index().pos(), "array index must be an int, got " + describe(idx.type()));
      }
      code = recv + "[" + idx.code() + "]";
      result = normalize(((ArrayType) t).getComponentType());
    } else if (t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.util.List")) {
      if (!isIntIndexBoxed(idx.type())) {
        return error(i.index().pos(), "list index must be an int, got " + describe(idx.type()));
      }
      code = recv + ".get(" + idx.code() + ")";
      result = typeArg(t, "java.util.List", 0);
    } else if (t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.util.Map")) {
      TypeMirror k = typeArg(t, "java.util.Map", 0);
      result = typeArg(t, "java.util.Map", 1);
      if (k != null && !types().isAssignable(idx.type(), k)) {
        return error(i.index().pos(), "map key must be a " + k + ", got " + describe(idx.type()));
      }
      code = recv + ".get(" + idx.code() + ")";
    } else {
      return error(i.pos(), "cannot index " + describe(t) + ": [...] works on a List, an array or a Map");
    }
    if (result == null) {
      return error(i.pos(), "cannot index raw type " + t + ": declare its type arguments");
    }
    Optional<String> bad = safety.neverViewSafeReason(result);
    if (bad.isPresent() && !pathOnly) {
      return error(i.pos(), "cannot index " + t + ": " + bad.get());
    }
    if (i.nullSafe()) {
      result = box(result);
      code = "((" + JavaStrings.typeName(result) + ") " + VALUES + ".nav(" + target.code() + ", " + recv + " -> " + code + "))";
    }
    return new CompiledExpr(code, result, i.pos());
  }

  private CompiledExpr function(String ns, String name, List<Expr> args, SourcePos pos, Scope scope) {
    List<CompiledExpr> compiled = new ArrayList<>();
    boolean failed = false;
    // strings.* and html.* take Object and call its toString(): check their arguments as text (#802).
    boolean text = ns.equals("strings") || ns.equals("html");
    for (Expr a : args) {
      CompiledExpr c = text ? compileOutput(a, scope) : compile(a, scope);
      failed |= c == null;
      compiled.add(c);
    }
    if (failed) {
      return null;
    }
    Optional<TypeElement> owner = functions().namespaceClass(ns);
    if (!owner.isPresent()) {
      return error(pos, "unknown function namespace '" + ns + "': register a class annotated @TemplateFunctions(\"" + ns
          + "\") (built-ins: " + String.join(", ", BuiltinNamespaces.NAMES) + "; auth with the Spring starter)");
    }
    if (functions().overloads(ns, name).isEmpty()) {
      return error(
          pos,
          "unknown function " + ns + "." + name + ": only public static @TemplateFunction methods of "
              + owner.get().getQualifiedName() + " can be called");
    }
    List<TypeMirror> argTypes = compiled.stream().map(CompiledExpr::type).collect(Collectors.toList());
    Optional<ExecutableElement> m = functions().lookup(ns, name, argTypes);
    if (!m.isPresent()) {
      return error(
          pos,
          "no single applicable " + ns + "." + name + " for arguments ("
              + argTypes.stream().map(ExpressionCompiler::describe).collect(Collectors.joining(", ")) + "): check the"
              + " argument types against the @TemplateFunction overloads of " + owner.get().getQualifiedName());
    }
    TypeMirror result = normalize(m.get().getReturnType());
    Optional<String> bad = safety.neverViewSafeReason(result);
    if (bad.isPresent()) {
      return error(pos, ns + "." + name + " returns " + result + ": " + bad.get());
    }
    if (inaccessibleResult(result, ns + "." + name + "(...)", pos)) {
      return null;
    }
    List<String> argCode = new ArrayList<>();
    if (functions().takesLocale(m.get())) {
      argCode.add("ctx.locale()");
    }
    compiled.forEach(c -> argCode.add(c.code()));
    String code = owner.get().getQualifiedName() + "." + name + "(" + String.join(", ", argCode) + ")";
    return new CompiledExpr(code, result, pos);
  }

  private CompiledExpr unary(Expr.Unary u, Scope scope) {
    if (u.op() == Expr.UnaryOp.NOT) {
      CompiledExpr c = compileCondition(u.operand(), scope);
      return c == null ? null : new CompiledExpr("(!" + c.code() + ")", c.type(), u.pos());
    }
    CompiledExpr x = compile(u.operand(), scope);
    if (x == null || nullSafe(u.operand())) {
      return null;
    }
    if (!isNumeric(x.type())) {
      return error(u.pos(), "unary - needs a number, got " + describe(x.type()));
    }
    return new CompiledExpr("(-" + x.code() + ")", promote(unboxed(x.type()), prim(TypeKind.INT)), u.pos());
  }

  private CompiledExpr binary(Expr.Binary b, Scope scope) {
    if (b.op() == Expr.BinaryOp.AND || b.op() == Expr.BinaryOp.OR) {
      CompiledExpr l = compileCondition(b.left(), scope);
      CompiledExpr r = compileCondition(b.right(), scope);
      if (l == null || r == null) {
        return null;
      }
      String op = b.op() == Expr.BinaryOp.AND ? " && " : " || ";
      return new CompiledExpr("(" + l.code() + op + r.code() + ")", prim(TypeKind.BOOLEAN), b.pos());
    }
    CompiledExpr l = compile(b.left(), scope);
    CompiledExpr r = compile(b.right(), scope);
    if (l == null || r == null) {
      return null;
    }
    boolean concat = isDeclared(l.type(), "java.lang.String") || isDeclared(r.type(), "java.lang.String");
    boolean eq = b.op() == Expr.BinaryOp.EQ || b.op() == Expr.BinaryOp.NE;
    if (!eq && !(concat && b.op() == Expr.BinaryOp.ADD) && (nullSafe(b.left()) | nullSafe(b.right()))) {
      return null;
    }
    switch (b.op()) {
      case EQ:
      case NE:
        return equality(b, l, r);
      case LT:
      case GT:
      case LE:
      case GE:
        return comparison(b, l, r);
      case ADD:
        return concat ? concat(b, l, r) : arithmetic(b, l, r);
      default:
        return arithmetic(b, l, r);
    }
  }

  /** Reports {@code e} if it is an {@code a?.b}, whose null Java would unbox or dereference. */
  private boolean nullSafe(Expr e) {
    boolean ns = e instanceof Expr.Property && ((Expr.Property) e).nullSafe()
        || e instanceof Expr.MethodCall && ((Expr.MethodCall) e).nullSafe();
    if (ns) {
      error(e.pos(), "a ?. result may be null, so it can't be compared with <, >, <=, >= or used in arithmetic:"
          + " give it a default ((u?.age ?: 0) > 18) or test first (u != null and u.age > 18)");
    }
    return ns;
  }

  private CompiledExpr equality(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    boolean ne = b.op() == Expr.BinaryOp.NE;
    if (isNot(b.left()) && r.type().getKind() == TypeKind.NULL || isNot(b.right()) && l.type().getKind() == TypeKind.NULL) {
      // As in Thymeleaf, 'not a != null' is '(not a) != null': a boolean, never null.
      String op = ne ? "!=" : "==";
      return error(b.pos(), "'not' binds tighter than " + op + ", so 'not x " + op + " null' compares a boolean with null"
          + " and is always " + ne + ": write x " + (ne ? "==" : "!=") + " null, or not (x " + op + " null)");
    }
    String code;
    if (isEnum(l.type()) && stringLiteral(b.right()) != null) {
      code = enumEquals(l, stringLiteral(b.right()), b.right().pos());
    } else if (isEnum(r.type()) && stringLiteral(b.left()) != null) {
      code = enumEquals(r, stringLiteral(b.left()), b.left().pos());
    } else if (l.type().getKind().isPrimitive() && r.type().getKind().isPrimitive()) {
      boolean bothBool = l.type().getKind() == TypeKind.BOOLEAN && r.type().getKind() == TypeKind.BOOLEAN;
      if (!bothBool && !(isNumeric(l.type()) && isNumeric(r.type()))) {
        return error(b.pos(), "cannot compare " + l.type() + " with " + r.type() + ": == on primitives needs two numbers or"
            + " two booleans");
      }
      code = "(" + l.code() + " == " + r.code() + ")";
    } else if (isNumber(l.type()) && isNumber(r.type())) {
      code = VALUES + ".numEquals(" + l.code() + ", " + r.code() + ")";
    } else if (neverEqual(l.type(), r.type())) {
      // javac's rule for reference ==: no value of one type can be a value of the other.
      return error(b.pos(), "cannot compare " + describe(l.type()) + " with " + describe(r.type()) + ": no value of one is"
          + " ever equal to the other, so this is always " + ne + "; compare an enum with a"
          + " string literal (status == 'ACTIVE'), or give both sides the same type in Java");
    } else {
      code = "java.util.Objects.equals(" + l.code() + ", " + r.code() + ")";
    }
    if (code == null) {
      return null;
    }
    if (ne) {
      code = "(!" + code + ")";
    }
    return new CompiledExpr(code, prim(TypeKind.BOOLEAN), b.pos());
  }

  private static boolean isNot(Expr e) {
    return e instanceof Expr.Unary && ((Expr.Unary) e).op() == Expr.UnaryOp.NOT;
  }

  /** {@code status == 'ACTIVE'}: checks the constant exists and compares by name. */
  private String enumEquals(CompiledExpr e, String constant, SourcePos pos) {
    TypeElement te = (TypeElement) types().asElement(e.type());
    List<String> constants = new ArrayList<>();
    for (Element el : te.getEnclosedElements()) {
      if (el.getKind() == ElementKind.ENUM_CONSTANT) {
        constants.add(el.getSimpleName().toString());
      }
    }
    if (!constants.contains(constant)) {
      env.diagnostics()
          .error(pos, "'" + constant + "' is not a constant of enum " + te.getQualifiedName() + " (constants: " + String.join(", ", constants) + ")");
      return null;
    }
    return "java.util.Objects.equals(" + VALUES + ".enumName(" + e.code() + "), " + JavaStrings.literal(constant) + ")";
  }

  private CompiledExpr comparison(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    Expr.BinaryOp o = b.op();
    String op = o == Expr.BinaryOp.LT ? " < " : o == Expr.BinaryOp.GT ? " > " : o == Expr.BinaryOp.LE ? " <= " : " >= ";
    String code;
    // price > 100: a BigDecimal compares with a number as BigDecimal.valueOf(number) (#806).
    if (isDeclared(l.type(), BIG_DECIMAL) && isNumeric(r.type())) {
      r = new CompiledExpr(BIG_DECIMAL + ".valueOf(" + r.code() + ")", l.type(), r.pos());
    } else if (isDeclared(r.type(), BIG_DECIMAL) && isNumeric(l.type())) {
      l = new CompiledExpr(BIG_DECIMAL + ".valueOf(" + l.code() + ")", r.type(), l.pos());
    }
    if (isNumeric(l.type()) && isNumeric(r.type())) {
      code = "(" + l.code() + op + r.code() + ")";
    } else if (l.type().getKind() == TypeKind.DECLARED
        && r.type().getKind() == TypeKind.DECLARED
        // l.compareTo(r) compiles only when l is a Comparable<? super R>.
        && types().isAssignable(l.type(), types().getDeclaredType(elements().getTypeElement("java.lang.Comparable"), types().getWildcardType(null, r.type())))) {
      code = "(" + l.code() + ".compareTo(" + r.code() + ")" + op + "0)";
    } else {
      return error(b.pos(), "cannot compare " + describe(l.type()) + " and " + describe(r.type()) + " with" + JavaStrings.stripJavaTrailing(op));
    }
    return new CompiledExpr(code, prim(TypeKind.BOOLEAN), b.pos());
  }

  private CompiledExpr concat(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    boolean ok = checkOutputType(l.type(), b.left().pos(), true);
    ok &= checkOutputType(r.type(), b.right().pos(), true);
    return ok ? new CompiledExpr("(" + l.code() + " + " + r.code() + ")", declared("java.lang.String"), b.pos()) : null;
  }

  private CompiledExpr arithmetic(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    Expr.BinaryOp o = b.op();
    String op =
        o == Expr.BinaryOp.ADD
            ? " + "
            : o == Expr.BinaryOp.SUB ? " - " : o == Expr.BinaryOp.MUL ? " * " : o == Expr.BinaryOp.DIV ? " / " : " % ";
    if (!isNumeric(l.type()) || !isNumeric(r.type())) {
      return error(b.pos(), "operator" + JavaStrings.stripJavaTrailing(op) + " needs numbers (or a String for +), got " + describe(l.type()) + " and " + describe(r.type())
          + (isDeclared(l.type(), BIG_DECIMAL) || isDeclared(r.type(), BIG_DECIMAL)
              ? ": templates do no BigDecimal arithmetic, so compute the value in the view type or a @TemplateFunction" : ""));
    }
    TypeMirror t = promote(unboxed(l.type()), unboxed(r.type()));
    return new CompiledExpr("(" + l.code() + op + r.code() + ")", t, b.pos());
  }

  private CompiledExpr conditional(Expr.Conditional c, Scope scope) {
    CompiledExpr cond = compileCondition(c.condition(), scope);
    CompiledExpr then = compile(c.then(), scope);
    CompiledExpr other =
        c.otherwise() == null ? new CompiledExpr("null", types().getNullType(), c.pos()) : compile(c.otherwise(), scope);
    if (cond == null || then == null || other == null) {
      return null;
    }
    TypeMirror t = unify(then.type(), other.type());
    if (t == null) {
      return error(c.pos(), "the branches of ?: have incompatible types " + describe(then.type()) + " and "
          + describe(other.type()) + ": make both branches the same type");
    }
    String code = "(" + cond.code() + " ? " + coerce(then, t) + " : " + coerce(other, t) + ")";
    return new CompiledExpr(code, t, c.pos());
  }

  private CompiledExpr elvis(Expr.Elvis e, Scope scope) {
    CompiledExpr v = compile(e.value(), scope);
    CompiledExpr f = compile(e.fallback(), scope);
    if (v == null || f == null) {
      return null;
    }
    if (v.type().getKind().isPrimitive()) {
      return v; // never null
    }
    if (v.type().getKind() == TypeKind.NULL) {
      return f;
    }
    TypeMirror t = unify(v.type(), f.type());
    if (t != null && t.getKind().isPrimitive()) {
      t = box(t);
    }
    if (t == null || !types().isAssignable(v.type(), t) || !types().isAssignable(f.type(), t)) {
      return error(e.pos(), "the operands of ?: have incompatible types " + describe(v.type()) + " and "
          + describe(f.type()) + ": make the default the same type as the value");
    }
    if (e.value() instanceof Expr.Var) { // a local read: a ternary evaluates the fallback only when needed
      return new CompiledExpr("(" + v.code() + " != null ? " + coerce(v, t) + " : " + coerce(f, t) + ")", t, e.pos());
    }
    return new CompiledExpr(VALUES + ".<" + JavaStrings.typeName(t) + ">elvis(" + v.code() + ", " + f.code() + ")", t, e.pos());
  }

  private CompiledExpr interpolation(Expr.Interpolation i, Scope scope) {
    StringBuilder code = new StringBuilder("(\"\"");
    boolean ok = true;
    for (Expr part : i.parts()) {
      if (part instanceof Expr.Literal && ((Expr.Literal) part).value() instanceof String) {
        code.append(" + ").append(JavaStrings.literal((String) ((Expr.Literal) part).value()));
        continue;
      }
      CompiledExpr c = compileOutput(part, scope);
      if (c == null) {
        ok = false;
        continue;
      }
      code.append(" + ").append(c.code());
    }
    code.append(')');
    return ok ? new CompiledExpr(code.toString(), declared("java.lang.String"), i.pos()) : null;
  }

  // ---------------------------------------------------------------- helpers

  private Types types() {
    return env.types();
  }

  private Elements elements() {
    return env.elements();
  }

  private <T> T error(SourcePos pos, String message) {
    env.diagnostics().error(pos, message);
    return null;
  }

  private CompiledExpr objectMethodError(String name, SourcePos pos) {
    return error(pos, "'" + name + "' is a java.lang.Object method and can never be called from a template");
  }

  private boolean isNamespace(String name) {
    return name.equals(FunctionRegistry.FIELDS) || name.equals(FunctionRegistry.IDS) || functions().isNamespace(name);
  }

  /** {@code ns.name(args)}: the reserved {@code fields} and {@code ids} namespaces, else a registered function. */
  private CompiledExpr callNamespace(String ns, String name, List<Expr> args, SourcePos pos, Scope scope) {
    if (ns.equals(FunctionRegistry.FIELDS)) {
      return FieldsCompiler.compile(name, args, pos, scope, this);
    }
    if (ns.equals(FunctionRegistry.IDS)) {
      return IdsCompiler.compile(name, args, pos, scope, this);
    }
    return function(ns, name, args, pos, scope);
  }

  private boolean isNamespaceRef(Expr.Var v, Scope scope) {
    return !scope.lookup(v.name()).isPresent() && isNamespace(v.name());
  }

  private PrimitiveType prim(TypeKind kind) {
    return types().getPrimitiveType(kind);
  }

  private TypeMirror declared(String name) {
    return elements().getTypeElement(name).asType();
  }

  /**
   * Whether {@code t} is the declared type named {@code qualifiedName} (any type arguments or type
   * annotations aside).
   */
  public static boolean isDeclared(TypeMirror t, String qualifiedName) {
    return t.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals(qualifiedName);
  }

  /** Whether {@code t}, erased, is a subtype of the erasure of the type named {@code name}. */
  public boolean isSubtypeOf(TypeMirror t, String name) {
    TypeElement te = elements().getTypeElement(name);
    return te != null && types().isSubtype(types().erasure(t), types().erasure(te.asType()));
  }

  private boolean isEnum(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED && ((DeclaredType) t).asElement().getKind() == ElementKind.ENUM;
  }

  /**
   * Whether two reference types (boxed, erased) can share no value: neither is a subtype of the
   * other, and no subclass could bridge them (an interface and a non-final class, or two
   * interfaces, could). A simplified form of javac's "incomparable types" rule.
   */
  private boolean neverEqual(TypeMirror a, TypeMirror b) {
    TypeMirror x = types().erasure(box(a));
    TypeMirror y = types().erasure(box(b));
    if (!isReference(x) || !isReference(y) || types().isSubtype(x, y) || types().isSubtype(y, x)) {
      return false;
    }
    return !(openTo(x, y) || openTo(y, x));
  }

  /** {@code i} is an interface that some subclass of {@code c} could implement. */
  private static boolean openTo(TypeMirror i, TypeMirror c) {
    return i.getKind() == TypeKind.DECLARED
        && c.getKind() == TypeKind.DECLARED
        && ((DeclaredType) i).asElement().getKind().isInterface()
        && !((DeclaredType) c).asElement().getModifiers().contains(Modifier.FINAL);
  }

  private static boolean isReference(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED || t.getKind() == TypeKind.ARRAY;
  }

  private static String stringLiteral(Expr e) {
    return e instanceof Expr.Literal && ((Expr.Literal) e).value() instanceof String
        ? (String) ((Expr.Literal) e).value()
        : null;
  }

  /** The primitive type of {@code t} (itself, or its unboxed type), or null. */
  private TypeMirror unboxed(TypeMirror t) {
    if (t.getKind().isPrimitive()) {
      return t;
    }
    if (t.getKind() == TypeKind.DECLARED) {
      try {
        return types().unboxedType(t);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }

  private TypeMirror box(TypeMirror t) {
    return t.getKind().isPrimitive() ? types().boxedClass((PrimitiveType) t).asType() : t;
  }

  /** Numeric primitive or box (char included, boolean not). */
  private boolean isNumeric(TypeMirror t) {
    TypeMirror u = unboxed(t);
    return u != null && u.getKind() != TypeKind.BOOLEAN;
  }

  /** A value that is a {@link Number} once boxed (char excluded). */
  private boolean isNumber(TypeMirror t) {
    if (t.getKind().isPrimitive()) {
      return t.getKind() != TypeKind.BOOLEAN && t.getKind() != TypeKind.CHAR;
    }
    return t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.lang.Number");
  }

  private static boolean isIntIndex(TypeMirror t) {
    TypeKind k = t.getKind();
    return k == TypeKind.INT || k == TypeKind.SHORT || k == TypeKind.BYTE || k == TypeKind.CHAR;
  }

  private boolean isIntIndexBoxed(TypeMirror t) {
    return isIntIndex(t) || (unboxed(t) != null && isIntIndex(unboxed(t)));
  }

  /** Binary numeric promotion of two primitive types. */
  private TypeMirror promote(TypeMirror a, TypeMirror b) {
    for (TypeKind k : Backports.listOf(TypeKind.DOUBLE, TypeKind.FLOAT, TypeKind.LONG)) {
      if (a.getKind() == k || b.getKind() == k) {
        return prim(k);
      }
    }
    return prim(TypeKind.INT);
  }

  /** The common type of two ?: branches, or null if incompatible. */
  private TypeMirror unify(TypeMirror a, TypeMirror b) {
    boolean an = a.getKind() == TypeKind.NULL;
    boolean bn = b.getKind() == TypeKind.NULL;
    if (an || bn) {
      return an && bn ? a : box(an ? b : a);
    }
    if (types().isSameType(a, b)) {
      return a;
    }
    boolean ap = a.getKind().isPrimitive();
    boolean bp = b.getKind().isPrimitive();
    if (ap && bp) {
      return isNumeric(a) && isNumeric(b) ? promote(a, b) : null;
    }
    if (ap != bp) {
      TypeMirror ua = unboxed(a);
      TypeMirror ub = unboxed(b);
      if (ua != null && ub != null && ua.getKind() == ub.getKind()) {
        return box(ap ? a : b);
      }
      return null;
    }
    if (types().isAssignable(a, b)) {
      return b;
    }
    if (types().isAssignable(b, a)) {
      return a;
    }
    return null;
  }

  private String coerce(CompiledExpr e, TypeMirror t) {
    if (t.getKind() == TypeKind.NULL || types().isSameType(e.type(), t)) {
      return e.code();
    }
    return "((" + JavaStrings.typeName(t) + ") " + e.code() + ")";
  }

  /** Strips wildcards, captured and declared type variables down to their upper bounds. */
  private TypeMirror normalize(TypeMirror t) {
    switch (t.getKind()) {
      case WILDCARD:
        TypeMirror ext = ((WildcardType) t).getExtendsBound();
        return ext != null ? normalize(ext) : declared(ViewSafety.OBJECT);
      case TYPEVAR:
        return normalize(((TypeVariable) t).getUpperBound());
      case INTERSECTION:
        return normalize(((IntersectionType) t).getBounds().get(0));
      default:
        return t;
    }
  }

  /** Type argument {@code index} of {@code t}'s supertype {@code name} (normalized), or null if raw. */
  private TypeMirror typeArg(TypeMirror t, String name, int index) {
    TypeMirror s = safety.supertype(t, name);
    if (s == null) {
      return null;
    }
    List<? extends TypeMirror> args = ((DeclaredType) s).getTypeArguments();
    return args.size() > index ? normalize(args.get(index)) : null;
  }

  /**
   * {@code ": its type argument X is ..."} naming the type argument that makes the container
   * {@code type} not view-safe, else {@code otherwise} (advice about {@code type} itself).
   */
  private String badTypeArgument(TypeMirror type, String otherwise) {
    Optional<String> access = safety.accessReason(type);
    if (access.isPresent()) {
      return ": " + access.get();
    }
    Optional<String> arg = safety.badTypeArgument(type);
    return arg.isPresent() ? ": " + arg.get() : otherwise;
  }

  /**
   * Reports at {@code pos} if {@code result}, the type of {@code what}, holds a class the generated
   * code can't name; otherwise javac would fail on the generated code with no template position.
   */
  private boolean inaccessibleResult(TypeMirror result, String what, SourcePos pos) {
    Optional<String> why = inaccessibleReason(result);
    if (why.isPresent()) {
      env.diagnostics().error(pos, what + " has type " + result + ", which is not view-safe: " + why.get());
    }
    return why.isPresent();
  }

  private static String describe(TypeMirror t) {
    return t.getKind() == TypeKind.NULL ? "null" : JavaStrings.typeName(t);
  }

  private static boolean isGetterName(String n) {
    return (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3)))
        || (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2)));
  }
}
