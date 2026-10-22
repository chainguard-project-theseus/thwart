package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.TypeRef;
import dev.tessera.compiler.forms.FieldsCompiler;
import dev.tessera.compiler.links.LinkCompiler;
import dev.tessera.compiler.messages.MessageCompiler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
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
 * reserved {@code fields} namespace to {@code FieldsCompiler}. All
 * methods report errors with the expression's position and return {@code null} on failure.
 *
 * <p>Emitted code is always a Java primary (parenthesized where needed), so callers may append
 * {@code .x()} or use it as an argument directly. Helpers live in {@code dev.tessera.runtime.Values}.
 */
public final class ExpressionCompiler {
  private static final String VALUES = "dev.tessera.runtime.Values";
  private static final String FRAGMENT = "dev.tessera.runtime.Fragment";
  private static final String FIELD = "dev.tessera.runtime.Field";
  private static final List<String> IMPLICIT_PACKAGES =
      List.of("java.lang", "java.util", "java.time", "java.math", "dev.tessera.runtime");
  private static final Set<String> OBJECT_METHODS =
      Set.of("getClass", "hashCode", "equals", "toString", "wait", "notify", "notifyAll", "clone", "finalize");
  private static final Map<String, TypeKind> PRIMITIVES =
      Map.of(
          "boolean", TypeKind.BOOLEAN,
          "byte", TypeKind.BYTE,
          "short", TypeKind.SHORT,
          "int", TypeKind.INT,
          "long", TypeKind.LONG,
          "char", TypeKind.CHAR,
          "float", TypeKind.FLOAT,
          "double", TypeKind.DOUBLE);

  private final CompilerEnv env;
  private final ViewSafety safety;
  private FunctionRegistry functions;
  private int lambdas;

  public ExpressionCompiler(CompilerEnv env) {
    this.env = env;
    this.safety = new ViewSafety(env);
  }

  public CompilerEnv env() {
    return env;
  }

  /**
   * The function namespaces visible to templates. Built (and its registration errors reported)
   * on first use, i.e. as soon as any expression or parameter type is compiled.
   */
  public FunctionRegistry functions() {
    if (functions == null) {
      functions = new FunctionRegistry(env);
    }
    return functions;
  }

  /** Compiles a value expression. */
  public CompiledExpr compile(Expr expr, Scope scope) {
    functions();
    return switch (expr) {
      case Expr.Literal l -> literal(l);
      case Expr.Var v -> variable(v, scope);
      case Expr.SelectionVar s -> selection(s, scope);
      case Expr.Property p -> property(p, scope);
      case Expr.MethodCall m -> methodCall(m, scope);
      case Expr.Index i -> index(i, scope);
      case Expr.FunctionCall f ->
          f.namespace().equals(FunctionRegistry.RESERVED)
              ? FieldsCompiler.compile(f.name(), f.args(), f.pos(), scope, this)
              : function(f.namespace(), f.name(), f.args(), f.pos(), scope);
      case Expr.Unary u -> unary(u, scope);
      case Expr.Binary b -> binary(b, scope);
      case Expr.Conditional c -> conditional(c, scope);
      case Expr.Elvis e -> elvis(e, scope);
      case Expr.Message m -> MessageCompiler.compile(m, scope, this);
      case Expr.Link l -> LinkCompiler.compile(l, scope, this);
      case Expr.Interpolation i -> interpolation(i, scope);
      case Expr.FragmentExpr f ->
          error(f.pos(), "a fragment expression ~{...} is only allowed in t:insert/t:replace or as a fragment argument");
    };
  }

  /**
   * Compiles a condition to Java code of type {@code boolean}: {@code boolean} as is, Boolean
   * null-safe, anything else via {@code dev.tessera.runtime.Conditions.isTrue}.
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
    } else if (isSame(t, "java.lang.Boolean")) {
      code = "java.lang.Boolean.TRUE.equals(" + c.code() + ")";
    } else {
      code = "dev.tessera.runtime.Conditions.isTrue(" + c.code() + ")";
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
      return errorType(iterable.pos(), "cannot iterate over " + describe(iterable.type()) + ": " + bad.get());
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
          return errorType(iterable.pos(), "cannot iterate over raw type " + t + ": declare its type arguments");
        }
        return types().getDeclaredType(elements().getTypeElement("java.util.Map.Entry"), k, v);
      }
      if (isSubtypeOf(t, "java.lang.Iterable")) {
        TypeMirror e = typeArg(t, "java.lang.Iterable", 0);
        if (e == null) {
          return errorType(iterable.pos(), "cannot iterate over raw type " + t + ": declare its type arguments");
        }
        return e;
      }
    }
    return errorType(iterable.pos(), "cannot iterate over " + describe(t) + ": t:each needs an Iterable, an array or a Map");
  }

  /**
   * Resolves a declared parameter type. Simple names resolve against {@code imports}
   * ({@code t:import} entries), then the implicit imports {@code java.lang}, {@code java.util},
   * {@code java.time}, {@code java.math}, {@code dev.tessera.runtime}. Parameters must be
   * view-safe, {@code SafeHtml}, {@code TrustedUrl}, {@code Fragment} or {@code Field} (fragment
   * parameters only; {@code TemplateIndex} rejects it in {@code t:params}).
   */
  public TypeMirror resolveType(TypeRef ref, List<String> imports) {
    functions();
    TypeMirror t = resolveRef(ref, imports);
    if (t == null) {
      return null;
    }
    Optional<String> never = safety.neverViewSafeReason(t);
    if (never.isPresent()) {
      return errorType(ref.pos(), "parameter type " + t + " is not allowed: " + never.get());
    }
    if (!safety.isViewSafe(t) && !isSame(t, FRAGMENT) && !isSame(t, FIELD)) {
      return errorType(
          ref.pos(),
          "parameter type " + t + " is not view-safe: use a record, an enum, a JDK value type, a collection of"
              + " those, or a class annotated @TemplateView");
    }
    return t;
  }

  /**
   * Checks that {@code type} may be output (view-safe or primitive, or a {@code Field}, which
   * outputs its path) and reports at {@code pos} if not.
   */
  public boolean checkOutputType(TypeMirror type, SourcePos pos) {
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
    if (!safety.isViewSafe(type) && !isSame(type, FIELD)) {
      env.diagnostics().error(pos, "cannot output a value of type " + type + ": it is not view-safe");
      return false;
    }
    return true;
  }

  // ---------------------------------------------------------------- expressions

  private CompiledExpr literal(Expr.Literal l) {
    Object v = l.value();
    return switch (v) {
      case null -> new CompiledExpr("null", types().getNullType(), l.pos());
      case String s -> new CompiledExpr(javaString(s), declared("java.lang.String"), l.pos());
      case Integer i -> new CompiledExpr(Integer.toString(i), prim(TypeKind.INT), l.pos());
      case Long n -> new CompiledExpr(n + "L", prim(TypeKind.LONG), l.pos());
      case Double d -> new CompiledExpr(Double.toString(d), prim(TypeKind.DOUBLE), l.pos());
      case Boolean b -> new CompiledExpr(Boolean.toString(b), prim(TypeKind.BOOLEAN), l.pos());
      default -> error(l.pos(), "unsupported literal " + v);
    };
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
    if (sel.isEmpty()) {
      return error(s.pos(), "*{" + s.name() + "} can only be used inside t:object: put t:object=\"...\" on this element or an"
          + " enclosing one, or write the full path (owner." + s.name() + ")");
    }
    return member(new CompiledExpr(sel.get().code(), sel.get().type(), s.pos()), s.name(), false, s.pos());
  }

  private CompiledExpr property(Expr.Property p, Scope scope) {
    if (p.target() instanceof Expr.Var v && isNamespaceRef(v, scope)) {
      return error(p.pos(), "'" + v.name() + "." + p.name() + "' is a function: call it as " + v.name() + "." + p.name() + "(...)");
    }
    CompiledExpr target = compile(p.target(), scope);
    return target == null ? null : member(target, p.name(), p.nullSafe(), p.pos());
  }

  private CompiledExpr methodCall(Expr.MethodCall m, Scope scope) {
    if (m.target() instanceof Expr.Var v && scope.lookup(v.name()).isEmpty()) {
      if (v.name().equals(FunctionRegistry.RESERVED)) {
        return FieldsCompiler.compile(m.name(), m.args(), m.pos(), scope, this);
      }
      if (functions().namespaces().contains(v.name())) {
        return function(v.name(), m.name(), m.args(), m.pos(), scope);
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
    return member(target, m.name(), m.nullSafe(), m.pos());
  }

  /** {@code target.name}, {@code target.name()} or {@code target.getName()}. */
  private CompiledExpr member(CompiledExpr target, String name, boolean nullSafe, SourcePos pos) {
    TypeMirror type = normalize(target.type());
    if (type.getKind() == TypeKind.NULL) {
      return error(pos, "cannot read '" + name + "' of null: the expression is the literal null");
    }
    if (type.getKind().isPrimitive()) {
      return error(pos, "cannot read '" + name + "' of a " + type + " value: primitives have no properties");
    }
    if (OBJECT_METHODS.contains(name) || ("get" + capitalize(name)).equals("getClass")) {
      return objectMethodError(name, pos);
    }
    Optional<String> never = safety.neverViewSafeReason(type);
    if (never.isPresent()) {
      return error(pos, "cannot read '" + name + "': " + never.get());
    }
    String suffix;
    TypeMirror result;
    Builtin builtin = builtin(type, name);
    if (builtin != null) {
      suffix = builtin.suffix();
      result = builtin.type();
      if (result == null) {
        return error(pos, "cannot read '" + name + "' of raw type " + type + ": declare its type arguments");
      }
    } else {
      if (!safety.isViewSafe(type)) {
        return error(
            pos,
            "cannot read '" + name + "': " + describe(type) + " is not view-safe (make it a record or annotate it"
                + " @TemplateView)");
      }
      ExecutableElement accessor = type.getKind() == TypeKind.DECLARED ? findAccessor((DeclaredType) type, name) : null;
      if (accessor == null) {
        return error(
            pos,
            "'" + name + "' is not a property of " + type + ": templates can read record components, public"
                + " getX()/isX() getters and built-ins (size, isEmpty, length, first, last)");
      }
      ExecutableType et = (ExecutableType) types().asMemberOf((DeclaredType) type, accessor);
      suffix = "." + accessor.getSimpleName() + "()";
      result = normalize(et.getReturnType());
    }
    Optional<String> bad = safety.neverViewSafeReason(result);
    if (bad.isPresent()) {
      return error(pos, "cannot read '" + name + "' of " + type + ": it returns " + result + ", and " + bad.get());
    }
    if (!nullSafe) {
      return new CompiledExpr(target.code() + suffix, result, pos);
    }
    TypeMirror boxed = box(result);
    String p = "v$$" + (++lambdas);
    String code = "((" + boxed + ") " + VALUES + ".nav(" + target.code() + ", " + p + " -> " + p + suffix + "))";
    return new CompiledExpr(code, boxed, pos);
  }

  /** A built-in member: Java code to append to the receiver and its type (null for a raw List element). */
  private record Builtin(String suffix, TypeMirror type) {}

  private Builtin builtin(TypeMirror type, String name) {
    if (type.getKind() == TypeKind.ARRAY) {
      return name.equals("length") ? new Builtin(".length", prim(TypeKind.INT)) : null;
    }
    if (type.getKind() != TypeKind.DECLARED) {
      return null;
    }
    boolean isString = isSame(type, "java.lang.String");
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
        return new Builtin(".getFirst()", typeArg(type, "java.util.List", 0));
      }
      if (name.equals("last") || name.equals("getLast")) {
        return new Builtin(".getLast()", typeArg(type, "java.util.List", 0));
      }
    }
    return null;
  }

  /** A record component accessor or public no-arg getter for {@code name}, or null. */
  private ExecutableElement findAccessor(DeclaredType type, String name) {
    TypeElement te = (TypeElement) type.asElement();
    Set<String> components = new HashSet<>();
    if (te.getKind() == ElementKind.RECORD) {
      for (RecordComponentElement rc : te.getRecordComponents()) {
        components.add(rc.getSimpleName().toString());
      }
    }
    List<String> candidates = new ArrayList<>();
    if (components.contains(name)) {
      candidates.add(name);
    }
    if (isGetterName(name)) {
      candidates.add(name);
      String prop = decapitalize(name.substring(name.startsWith("get") ? 3 : 2));
      if (components.contains(prop)) {
        candidates.add(prop);
      }
    } else {
      candidates.add("get" + capitalize(name));
      candidates.add("is" + capitalize(name));
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
    String code;
    TypeMirror result;
    if (t.getKind() == TypeKind.ARRAY) {
      if (!isIntIndexBoxed(idx.type())) {
        return error(i.index().pos(), "array index must be an int, got " + describe(idx.type()));
      }
      code = target.code() + "[" + idx.code() + "]";
      result = normalize(((ArrayType) t).getComponentType());
    } else if (t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.util.List")) {
      if (!isIntIndexBoxed(idx.type())) {
        return error(i.index().pos(), "list index must be an int, got " + describe(idx.type()));
      }
      code = target.code() + ".get(" + idx.code() + ")";
      result = typeArg(t, "java.util.List", 0);
    } else if (t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.util.Map")) {
      TypeMirror k = typeArg(t, "java.util.Map", 0);
      result = typeArg(t, "java.util.Map", 1);
      if (k != null && !types().isAssignable(idx.type(), k)) {
        return error(i.index().pos(), "map key must be a " + k + ", got " + describe(idx.type()));
      }
      code = target.code() + ".get(" + idx.code() + ")";
    } else {
      return error(i.pos(), "cannot index " + describe(t) + ": [...] works on a List, an array or a Map");
    }
    if (result == null) {
      return error(i.pos(), "cannot index raw type " + t + ": declare its type arguments");
    }
    Optional<String> bad = safety.neverViewSafeReason(result);
    if (bad.isPresent()) {
      return error(i.pos(), "cannot index " + t + ": " + bad.get());
    }
    return new CompiledExpr(code, result, i.pos());
  }

  private CompiledExpr function(String ns, String name, List<Expr> args, SourcePos pos, Scope scope) {
    List<CompiledExpr> compiled = new ArrayList<>();
    boolean failed = false;
    for (Expr a : args) {
      CompiledExpr c = compile(a, scope);
      failed |= c == null;
      compiled.add(c);
    }
    if (failed) {
      return null;
    }
    Optional<TypeElement> owner = functions().namespaceClass(ns);
    if (owner.isEmpty()) {
      return error(pos, "unknown function namespace '" + ns + "': register a class annotated @TemplateFunctions(\"" + ns
          + "\") (built-ins: strings, numbers, temporals, lists; auth with the Spring starter)");
    }
    if (functions().overloads(ns, name).isEmpty()) {
      return error(
          pos,
          "unknown function " + ns + "." + name + ": only public static @TemplateFunction methods of "
              + owner.get().getQualifiedName() + " can be called");
    }
    List<TypeMirror> argTypes = compiled.stream().map(CompiledExpr::type).toList();
    Optional<ExecutableElement> m = functions().lookup(ns, name, argTypes);
    if (m.isEmpty()) {
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
    String code =
        owner.get().getQualifiedName() + "." + name + "("
            + compiled.stream().map(CompiledExpr::code).collect(Collectors.joining(", ")) + ")";
    return new CompiledExpr(code, result, pos);
  }

  private CompiledExpr unary(Expr.Unary u, Scope scope) {
    if (u.op() == Expr.UnaryOp.NOT) {
      CompiledExpr c = compileCondition(u.operand(), scope);
      return c == null ? null : new CompiledExpr("(!" + c.code() + ")", c.type(), u.pos());
    }
    CompiledExpr x = compile(u.operand(), scope);
    if (x == null) {
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
    return switch (b.op()) {
      case EQ, NE -> equality(b, l, r);
      case LT, GT, LE, GE -> comparison(b, l, r);
      case ADD -> isSame(l.type(), "java.lang.String") || isSame(r.type(), "java.lang.String")
          ? concat(b, l, r)
          : arithmetic(b, l, r);
      default -> arithmetic(b, l, r);
    };
  }

  private CompiledExpr equality(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
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
    } else {
      code = "java.util.Objects.equals(" + l.code() + ", " + r.code() + ")";
    }
    if (code == null) {
      return null;
    }
    if (b.op() == Expr.BinaryOp.NE) {
      code = "(!" + code + ")";
    }
    return new CompiledExpr(code, prim(TypeKind.BOOLEAN), b.pos());
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
    return "java.util.Objects.equals(" + VALUES + ".enumName(" + e.code() + "), " + javaString(constant) + ")";
  }

  private CompiledExpr comparison(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    String op =
        switch (b.op()) {
          case LT -> " < ";
          case GT -> " > ";
          case LE -> " <= ";
          default -> " >= ";
        };
    String code;
    if (isNumeric(l.type()) && isNumeric(r.type())) {
      code = "(" + l.code() + op + r.code() + ")";
    } else if (isComparable(l.type())
        && r.type().getKind() == TypeKind.DECLARED
        && (types().isAssignable(r.type(), l.type()) || types().isAssignable(l.type(), r.type()))) {
      code = "(" + l.code() + ".compareTo(" + r.code() + ")" + op + "0)";
    } else {
      return error(b.pos(), "cannot compare " + describe(l.type()) + " and " + describe(r.type()) + " with" + op.stripTrailing());
    }
    return new CompiledExpr(code, prim(TypeKind.BOOLEAN), b.pos());
  }

  private CompiledExpr concat(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    boolean ok = checkOutputType(l.type(), b.left().pos());
    ok &= checkOutputType(r.type(), b.right().pos());
    return ok ? new CompiledExpr("(" + l.code() + " + " + r.code() + ")", declared("java.lang.String"), b.pos()) : null;
  }

  private CompiledExpr arithmetic(Expr.Binary b, CompiledExpr l, CompiledExpr r) {
    String op =
        switch (b.op()) {
          case ADD -> " + ";
          case SUB -> " - ";
          case MUL -> " * ";
          case DIV -> " / ";
          default -> " % ";
        };
    if (!isNumeric(l.type()) || !isNumeric(r.type())) {
      return error(b.pos(), "operator" + op.stripTrailing() + " needs numbers (or a String for +), got " + describe(l.type()) + " and " + describe(r.type()));
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
    return new CompiledExpr(VALUES + ".<" + t + ">elvis(" + v.code() + ", " + f.code() + ")", t, e.pos());
  }

  private CompiledExpr interpolation(Expr.Interpolation i, Scope scope) {
    StringBuilder code = new StringBuilder("(\"\"");
    boolean ok = true;
    for (Expr part : i.parts()) {
      if (part instanceof Expr.Literal l && l.value() instanceof String s) {
        code.append(" + ").append(javaString(s));
        continue;
      }
      CompiledExpr c = compile(part, scope);
      if (c == null || !checkOutputType(c.type(), part.pos())) {
        ok = false;
        continue;
      }
      code.append(" + ").append(c.code());
    }
    code.append(')');
    return ok ? new CompiledExpr(code.toString(), declared("java.lang.String"), i.pos()) : null;
  }

  // ---------------------------------------------------------------- type resolution

  private TypeMirror resolveRef(TypeRef ref, List<String> imports) {
    TypeMirror base;
    TypeKind pk = PRIMITIVES.get(ref.name());
    if (pk != null) {
      if (!ref.typeArgs().isEmpty()) {
        return errorType(ref.pos(), ref.name() + " cannot have type arguments");
      }
      base = prim(pk);
    } else {
      TypeElement te = resolveName(ref.name(), imports, ref.pos());
      if (te == null) {
        return null;
      }
      Optional<String> never = safety.neverViewSafeReason(types().erasure(te.asType()));
      if (never.isPresent()) {
        return errorType(ref.pos(), "parameter type " + te.getQualifiedName() + " is not allowed: " + never.get());
      }
      int expected = te.getTypeParameters().size();
      int got = ref.typeArgs().size();
      if (got == 0 && expected > 0) {
        return errorType(ref.pos(), ref.name() + " needs " + expected + " type argument(s), e.g. " + ref.name() + "<String>");
      }
      if (got != expected) {
        return errorType(ref.pos(), te.getQualifiedName() + " takes " + expected + " type argument(s), got " + got);
      }
      TypeMirror[] args = new TypeMirror[got];
      for (int i = 0; i < got; i++) {
        TypeRef a = ref.typeArgs().get(i);
        args[i] = resolveRef(a, imports);
        if (args[i] == null) {
          return null;
        }
        if (args[i].getKind().isPrimitive()) {
          return errorType(a.pos(), "type arguments must be reference types: use " + box(args[i]) + ", not " + a.name());
        }
      }
      base = types().getDeclaredType(te, args);
    }
    for (int d = 0; d < ref.arrayDims(); d++) {
      base = types().getArrayType(base);
    }
    return base;
  }

  private TypeElement resolveName(String name, List<String> imports, SourcePos pos) {
    if (name.contains(".")) {
      TypeElement te = elements().getTypeElement(name);
      if (te != null) {
        return te;
      }
      int dot = name.indexOf('.');
      TypeElement outer = resolveSimple(name.substring(0, dot), imports, pos, false);
      if (outer != null) {
        te = elements().getTypeElement(outer.getQualifiedName() + name.substring(dot));
        if (te != null) {
          return te;
        }
      }
      env.diagnostics().error(pos, "unknown type '" + name + "': check the package name and that the class is on the compile"
          + " classpath");
      return null;
    }
    return resolveSimple(name, imports, pos, true);
  }

  private TypeElement resolveSimple(String simple, List<String> imports, SourcePos pos, boolean report) {
    for (String raw : imports) {
      String imp = raw.strip();
      if (!imp.endsWith(".*") && (imp.equals(simple) || imp.endsWith("." + simple))) {
        TypeElement te = elements().getTypeElement(imp);
        if (te != null) {
          return te;
        }
        if (report) {
          env.diagnostics().error(pos, "unknown type '" + imp + "' in t:import: check the package name and that the class is on"
              + " the compile classpath");
        }
        return null;
      }
    }
    List<TypeElement> found = new ArrayList<>();
    for (String raw : imports) {
      String imp = raw.strip();
      if (imp.endsWith(".*")) {
        TypeElement te = elements().getTypeElement(imp.substring(0, imp.length() - 1) + simple);
        if (te != null && !found.contains(te)) {
          found.add(te);
        }
      }
    }
    if (found.size() == 1) {
      return found.get(0);
    }
    if (found.size() > 1) {
      if (report) {
        env.diagnostics()
            .error(pos, "type '" + simple + "' is ambiguous: " + found.stream().map(t -> t.getQualifiedName().toString()).collect(Collectors.joining(" and ")) + "; import one explicitly");
      }
      return null;
    }
    for (String pkg : IMPLICIT_PACKAGES) {
      TypeElement te = elements().getTypeElement(pkg + "." + simple);
      if (te != null) {
        return te;
      }
    }
    if (report) {
      env.diagnostics().error(pos, "unknown type '" + simple + "': add it to t:import or use its qualified name");
    }
    return null;
  }

  // ---------------------------------------------------------------- helpers

  private Types types() {
    return env.types();
  }

  private Elements elements() {
    return env.elements();
  }

  private CompiledExpr error(SourcePos pos, String message) {
    env.diagnostics().error(pos, message);
    return null;
  }

  private TypeMirror errorType(SourcePos pos, String message) {
    env.diagnostics().error(pos, message);
    return null;
  }

  private CompiledExpr objectMethodError(String name, SourcePos pos) {
    return error(pos, "'" + name + "' is a java.lang.Object method and can never be called from a template");
  }

  private boolean isNamespace(String name) {
    return name.equals(FunctionRegistry.RESERVED) || functions().namespaces().contains(name);
  }

  private boolean isNamespaceRef(Expr.Var v, Scope scope) {
    return scope.lookup(v.name()).isEmpty() && isNamespace(v.name());
  }

  private PrimitiveType prim(TypeKind kind) {
    return types().getPrimitiveType(kind);
  }

  private TypeMirror declared(String name) {
    return elements().getTypeElement(name).asType();
  }

  private boolean isSame(TypeMirror t, String name) {
    return t.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals(name);
  }

  private boolean isSubtypeOf(TypeMirror t, String name) {
    TypeElement te = elements().getTypeElement(name);
    return te != null && types().isSubtype(types().erasure(t), types().erasure(te.asType()));
  }

  private boolean isEnum(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED && ((DeclaredType) t).asElement().getKind() == ElementKind.ENUM;
  }

  private boolean isComparable(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED && isSubtypeOf(t, "java.lang.Comparable");
  }

  private static String stringLiteral(Expr e) {
    return e instanceof Expr.Literal l && l.value() instanceof String s ? s : null;
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
    return switch (t.getKind()) {
      case INT, SHORT, BYTE, CHAR -> true;
      default -> false;
    };
  }

  private boolean isIntIndexBoxed(TypeMirror t) {
    return isIntIndex(t) || (unboxed(t) != null && isIntIndex(unboxed(t)));
  }

  /** Binary numeric promotion of two primitive types. */
  private TypeMirror promote(TypeMirror a, TypeMirror b) {
    for (TypeKind k : List.of(TypeKind.DOUBLE, TypeKind.FLOAT, TypeKind.LONG)) {
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
    return "((" + t + ") " + e.code() + ")";
  }

  /** Strips wildcards, captured and declared type variables down to their upper bounds. */
  private TypeMirror normalize(TypeMirror t) {
    return switch (t.getKind()) {
      case WILDCARD -> {
        TypeMirror ext = ((WildcardType) t).getExtendsBound();
        yield ext != null ? normalize(ext) : declared(ViewSafety.OBJECT);
      }
      case TYPEVAR -> normalize(((TypeVariable) t).getUpperBound());
      case INTERSECTION -> normalize(((IntersectionType) t).getBounds().get(0));
      default -> t;
    };
  }

  /** Type argument {@code index} of {@code t}'s supertype {@code name} (normalized), or null if raw. */
  private TypeMirror typeArg(TypeMirror t, String name, int index) {
    Deque<TypeMirror> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(t);
    while (!work.isEmpty()) {
      TypeMirror s = work.poll();
      if (s.getKind() != TypeKind.DECLARED || !seen.add(s.toString())) {
        continue;
      }
      DeclaredType dt = (DeclaredType) s;
      if (((TypeElement) dt.asElement()).getQualifiedName().contentEquals(name)) {
        List<? extends TypeMirror> args = dt.getTypeArguments();
        return args.size() > index ? normalize(args.get(index)) : null;
      }
      work.addAll(types().directSupertypes(s));
    }
    return null;
  }

  private static String describe(TypeMirror t) {
    return t.getKind() == TypeKind.NULL ? "null" : t.toString();
  }

  private static boolean isGetterName(String n) {
    return (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3)))
        || (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2)));
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** JavaBeans decapitalization: {@code Name} to {@code name}, {@code URL} stays {@code URL}. */
  private static String decapitalize(String s) {
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
      return s;
    }
    return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  /** A Java string literal for {@code s}, safe against unicode-escape preprocessing. */
  public static String javaString(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\%03o", (int) c));
          } else if (c >= 0x7f) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
