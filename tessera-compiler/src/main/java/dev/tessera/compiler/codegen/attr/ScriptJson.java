package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ViewSafety;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Serializes {@code [[...]]} and {@code t:text} values inside {@code <script>}.
 *
 * <p>Values without beans go directly to {@code Escapers.javaScript} ({@code Escapers.json} in a
 * JSON {@code <script>}). Records and
 * {@code @TemplateView} classes use generated static helpers that append JSON to one private
 * buffer, without building intermediate maps or lists. Only the completed buffer is written to
 * the output. Properties are
 * the record components and the public no-arg {@code getX()} (and boolean {@code isX()})
 * getters, the members templates can read, named as Jackson (Thymeleaf's serializer) names
 * them and in its order where that is defined: record components, else properties backed by a
 * field in field order, then getter-only properties (in declaration order here; Jackson's order
 * for those depends on reflection). A getter whose return type is never view-safe is left out; any
 * other property type that cannot be serialized is a build error. There is no reflection: the
 * set of properties is fixed at build time by the static type. Each property is serialized before
 * the next getter is called; view getters should not mutate previously returned values.
 *
 * <p>Recursive types (a tree node with a {@code List} of children) are allowed; nesting deeper
 * than {@value #MAX_DEPTH} levels, which includes a cycle, throws
 * {@code IllegalArgumentException} before anything is written.
 */
final class ScriptJson {
  /** Same limit as {@code Escapers.json}. */
  static final int MAX_DEPTH = 64;

  private static final String TEMPLATE_VIEW = "dev.tessera.runtime.TemplateView";
  private static final String OBJECT = "java.lang.Object";

  /**
   * {@code java.time} types {@code Escapers.json} serializes as an ISO string. Must mirror the
   * java.time cases of {@code Escapers.writeJson} exactly (ZoneOffset is handled there by
   * {@code case ZoneId}); java.time enums (DayOfWeek, Month) take the enum path.
   */
  private static final Set<String> JSON_TIME =
      Backports.setOf(
          "java.time.Instant",
          "java.time.LocalDate",
          "java.time.LocalTime",
          "java.time.LocalDateTime",
          "java.time.OffsetTime",
          "java.time.OffsetDateTime",
          "java.time.ZonedDateTime",
          "java.time.Year",
          "java.time.YearMonth",
          "java.time.MonthDay",
          "java.time.Duration",
          "java.time.Period",
          "java.time.ZoneId",
          "java.time.ZoneOffset");

  private static final Set<String> JSON_COLLECTIONS =
      Backports.setOf("java.util.Collection", "java.util.List", "java.util.Set");

  private final CodegenContext ctx;
  /** Generated helper method per type (by {@code TypeMirror.toString()}), for reuse and recursion. */
  private final Map<String, String> helpers = new HashMap<>();
  /** Beans whose helper is being generated, to stop unbounded generic expansion. */
  private final Set<String> generating = new HashSet<>();
  /** Why a nested property cannot be serialized, for the error message; null at top level. */
  private String failure;

  private ScriptJson(CodegenContext ctx) {
    this.ctx = ctx;
  }

  /**
   * A statement writing the whole JSON value, or null after reporting an error. A value without
   * beans goes to {@code Escapers.javaScript}, which writes it in parentheses, or in a JSON
   * {@code <script>} ({@link CodegenContext#inJsonScript}) to {@code Escapers.json}. A value with
   * beans goes to a generated helper, which writes it in parentheses too outside a JSON script.
   */
  static Emitted statement(CodegenContext ctx, CompiledExpr c) {
    ScriptJson json = new ScriptJson(ctx);
    String code = json.value(c.type(), "o$", "0", "0");
    if (code == null) {
      json.report(c.pos(), c.type(), "inside <script>");
      return null;
    }
    if (code.equals("o$")) {
      Skeleton.Escaper escaper = ctx.inJsonScript() ? Skeleton.Escaper.JSON : Skeleton.Escaper.JAVASCRIPT;
      return Emitted.hole(escaper, null, c.pos(), c.code()).then("\n");
    }
    String helper = ctx.freshName("jsonOutput");
    boolean js = !ctx.inJsonScript();
    ctx.addMember(Emitted.jsonHelperMethod(helper, code, js));
    return Emitted.jsonHelperHole(helper, c.pos(), c.code(), js).then("\n");
  }

  private void report(SourcePos pos, TypeMirror type, String where) {
    ctx.diagnostics()
        .error(
            pos,
            "cannot output a value of type " + type + " " + where + ": only strings, numbers,"
                + " booleans, enums, java.time values, records and @TemplateView classes of those, and"
                + " Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON"
                + (failure == null ? "" : "; " + failure));
  }

  /**
   * Code for {@code code} (of type {@code t}): {@code code} itself if {@code t} holds no beans, else
   * a call of a generated helper that appends the JSON to {@code s$}; null if {@code t} cannot be
   * serialized. {@code depth} limits the
   * conversion and {@code jsonDepth} the JSON nesting: a bean-containing Optional is unwrapped
   * before JSON serialization.
   */
  private String value(TypeMirror t, String code, String depth, String jsonDepth) {
    switch (t.getKind()) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case FLOAT:
      case DOUBLE:
      case NULL:
        return code;
      case WILDCARD: {
        TypeMirror bound = ((WildcardType) t).getExtendsBound();
        return bound == null ? null : value(bound, code, depth, jsonDepth);
      }
      case TYPEVAR:
        return value(types().erasure(t), code, depth, jsonDepth);
      case ARRAY: {
        TypeMirror component = ((ArrayType) t).getComponentType();
        if (component.getKind().isPrimitive()) {
          return code;
        }
        String item = value(component, "e$", "d$ + 1", "j$ + 1");
        if (item == null) {
          return null;
        }
        return item.equals("e$") ? code : container(t, code, depth, jsonDepth,
            listBody("(java.lang.Object[]) o$", item));
      }
      case DECLARED:
        break;
      default:
        return null;
    }
    t = ctx.expressions().containerView(t); // an ArrayList or TreeMap as its List or Map (#135)
    DeclaredType dt = (DeclaredType) t;
    TypeElement te = (TypeElement) dt.asElement();
    String name = te.getQualifiedName().toString();
    if (te.getKind() == ElementKind.ENUM || ViewSafety.isJdkValueType(name) || JSON_TIME.contains(name)) {
      return code;
    }
    List<? extends TypeMirror> args = dt.getTypeArguments();
    if (name.equals("java.util.Optional") || JSON_COLLECTIONS.contains(name)) {
      boolean optional = name.equals("java.util.Optional");
      String item = args.size() == 1
          ? value(args.get(0), "e$", "d$ + 1", optional ? "j$" : "j$ + 1") : null;
      if (item == null) {
        return null;
      }
      if (item.equals("e$")) {
        return code;
      }
      return container(t, code, depth, jsonDepth, optional
          ? "java.lang.Object e$ = ((java.util.Optional<?>) o$).orElse(null);\n" + item + ";\n"
          : listBody("(java.util.Collection<?>) o$", item));
    }
    if (name.equals("java.util.Map")) {
      String item = args.size() == 2 && isString(args.get(0))
          ? value(args.get(1), "e$", "d$ + 1", "j$ + 1") : null;
      if (item == null) {
        return null;
      }
      String loop = "for (java.util.Map.Entry<?, ?> x$ : ((java.util.Map<?, ?>) o$).entrySet()) {\n"
          + "java.lang.Object k$ = x$.getKey();\n"
          + "if (!(k$ instanceof java.lang.String)) { throw new java.lang.IllegalArgumentException("
          + "\"JSON object keys must be Strings\"); }\n";
      return item.equals("e$") ? code : container(t, code, depth, jsonDepth,
          "s$.append('{');\nboolean first$ = true;\n" + loop
              + "if (!first$) { s$.append(','); }\nfirst$ = false;\n"
              + "dev.tessera.runtime.Escapers.appendJson(s$, k$, 0);\ns$.append(':');\n"
              + "java.lang.Object e$ = x$.getValue();\n" + item + ";\n}\ns$.append('}');\n");
    }
    if (isBean(te) && !ctx.expressions().neverViewSafeReason(t).isPresent() && accessible(t)) {
      return bean(dt, code, depth, jsonDepth);
    }
    return null;
  }

  private String listBody(String iterable, String item) {
    String loop = "for (java.lang.Object e$ : " + iterable + ") {\n";
    return "s$.append('[');\nboolean first$ = true;\n" + loop + "if (!first$) { s$.append(','); }\nfirst$ = false;\n"
            + item + ";\n}\ns$.append(']');\n";
  }

  /** A call of the helper for container type {@code t} with {@code body}. */
  private String container(TypeMirror t, String code, String depth, String jsonDepth, String body) {
    String key = t.toString();
    String helper = helpers.get(key);
    if (helper == null) {
      helper = ctx.freshName("json");
      helpers.put(key, helper);
      addHelper(helper, body);
    }
    return call(helper, code, depth, jsonDepth);
  }

  /** A call of the helper for bean {@code t}, or null. */
  private String bean(DeclaredType t, String code, String depth, String jsonDepth) {
    String key = t.toString();
    String helper = helpers.get(key);
    if (helper == null) {
      TypeElement te = (TypeElement) t.asElement();
      String element = te.getQualifiedName().toString();
      if (!generating.add(element)) {
        failure = t + " contains itself with different type arguments";
        return null;
      }
      helper = ctx.freshName("json");
      helpers.put(key, helper);
      String erased = JavaStrings.typeName(types().erasure(t));
      StringBuilder body = new StringBuilder();
      body.append(erased).append(" v$ = (").append(erased).append(") o$;\n");
      body.append("s$.append('{');\n");
      boolean first = true;
      for (Map.Entry<String, ExecutableElement> p : properties(te).entrySet()) {
        ExecutableElement getter = p.getValue();
        TypeMirror type = ((ExecutableType) types().asMemberOf(t, getter)).getReturnType();
        if (ctx.expressions().neverViewSafeReason(type).isPresent()) {
          continue; // never readable from a template, so never serialized either
        }
        String access = "v$." + getter.getSimpleName() + "()";
        String value = value(type, access, "d$ + 1", "j$ + 1");
        if (value == null) {
          if (failure == null) {
            failure = "property '" + p.getKey() + "' of " + t + " has type " + type;
          }
          return null;
        }
        StringOutput escapedKey = new StringOutput();
        Escapers.json(escapedKey, p.getKey());
        body.append("s$.append(").append(JavaStrings.literal((first ? "" : ",") + escapedKey + ":")).append(");\n");
        first = false;
        body.append(value.equals(access)
            ? "dev.tessera.runtime.Escapers.appendJson(s$, " + access + ", j$ + 1)" : value).append(";\n");
      }
      body.append("s$.append('}');\n");
      generating.remove(element);
      addHelper(helper, body.toString());
    }
    return call(helper, code, depth, jsonDepth);
  }

  private String call(String helper, String code, String depth, String jsonDepth) {
    return helper + "(" + code + ", " + depth + ", " + jsonDepth + ", s$)";
  }

  private void addHelper(String name, String body) {
    String tooDeep = ") { throw new java.lang.IllegalArgumentException(\"value nested deeper than " + MAX_DEPTH
        + " (cycle?)\"); }\n";
    ctx.addMember("private static void " + name + "(java.lang.Object o$, int d$, int j$, java.lang.StringBuilder s$) {\n"
            + "if (o$ == null) { dev.tessera.runtime.Escapers.appendJson(s$, null, j$); return; }\n"
            + "if (d$ > " + MAX_DEPTH + " || j$ > " + MAX_DEPTH + tooDeep + body + "}\n");
  }

  /**
   * The JSON properties of record or {@code @TemplateView} type {@code te} and their accessors,
   * in Jackson's default order: record components, else properties with a same-named field in
   * field order (superclass fields first), then the other getters.
   */
  private Map<String, ExecutableElement> properties(TypeElement te) {
    Map<String, ExecutableElement> getters = new LinkedHashMap<>();
    List<String> order = new ArrayList<>();
    boolean record = te.getKind().name().equals("RECORD");
    if (record) {
      List<ExecutableElement> methods = ElementFilter.methodsIn(te.getEnclosedElements());
      for (Element e : te.getEnclosedElements()) {
        if (e.getKind().name().equals("RECORD_COMPONENT")) {
          for (ExecutableElement m : methods) {
            if (m.getSimpleName().contentEquals(e.getSimpleName()) && m.getParameters().isEmpty()) {
              getters.put(e.getSimpleName().toString(), m);
            }
          }
        }
      }
    } else {
      Deque<TypeElement> classes = new ArrayDeque<>();
      for (TypeElement c = te; c != null && !c.getQualifiedName().contentEquals(OBJECT); c = superclass(c)) {
        classes.push(c);
      }
      for (TypeElement c : classes) {
        for (Element f : ElementFilter.fieldsIn(c.getEnclosedElements())) {
          if (!f.getModifiers().contains(Modifier.STATIC)) {
            order.add(f.getSimpleName().toString());
          }
        }
      }
    }
    // Getters, most-derived declaration first: te, then its supertypes breadth-first.
    Map<String, ExecutableElement> found = new LinkedHashMap<>();
    Deque<TypeElement> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(te);
    while (!work.isEmpty()) {
      TypeElement c = work.poll();
      if (!seen.add(c.getQualifiedName().toString()) || c.getQualifiedName().contentEquals(OBJECT)) {
        continue;
      }
      for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
        String prop = propertyName(m);
        if (prop != null && !found.containsKey(prop)) {
          found.put(prop, m);
        }
      }
      for (TypeMirror s : types().directSupertypes(c.asType())) {
        work.add((TypeElement) ((DeclaredType) s).asElement());
      }
    }
    for (String f : order) {
      if (found.containsKey(f) && !getters.containsKey(f)) {
        getters.put(f, found.get(f));
      }
    }
    for (Map.Entry<String, ExecutableElement> e : found.entrySet()) {
      if (!getters.containsKey(e.getKey())) {
        getters.put(e.getKey(), e.getValue());
      }
    }
    return getters;
  }

  private static TypeElement superclass(TypeElement c) {
    TypeMirror s = c.getSuperclass();
    return s.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) s).asElement() : null;
  }

  /**
   * The JSON property name of getter {@code m} as Jackson names it ({@code getFirstName} is
   * {@code firstName}, {@code getURL} is {@code url}), or null if {@code m} is not a public
   * no-arg instance {@code getX()}, or {@code isX()} returning {@code boolean} or
   * {@code Boolean}.
   */
  private static String propertyName(ExecutableElement m) {
    if (!m.getModifiers().contains(Modifier.PUBLIC)
        || m.getModifiers().contains(Modifier.STATIC)
        || !m.getParameters().isEmpty()
        || !m.getTypeParameters().isEmpty()
        || m.getReturnType().getKind() == TypeKind.VOID) {
      return null;
    }
    String n = m.getSimpleName().toString();
    String rest;
    if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
      rest = n.substring(3);
    } else if (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))
        && (m.getReturnType().getKind() == TypeKind.BOOLEAN || isDeclared(m.getReturnType(), "java.lang.Boolean"))) {
      rest = n.substring(2);
    } else {
      return null;
    }
    if (n.equals("getClass")) {
      return null;
    }
    return JavaStrings.lowerLeadingUpper(rest);
  }

  /** Whether the generated helper can name {@code t}; if not, says why in {@link #failure} (#269). */
  private boolean accessible(TypeMirror t) {
    Optional<String> why = ctx.expressions().inaccessibleReason(t);
    if (why.isPresent() && failure == null) {
      failure = why.get();
    }
    return !why.isPresent();
  }

  private static boolean isBean(TypeElement te) {
    if (te.getKind().name().equals("RECORD")) {
      return true;
    }
    for (AnnotationMirror am : te.getAnnotationMirrors()) {
      if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(TEMPLATE_VIEW)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isString(TypeMirror t) {
    if (t.getKind() == TypeKind.WILDCARD) {
      TypeMirror bound = ((WildcardType) t).getExtendsBound();
      return bound != null && isString(bound);
    }
    return isDeclared(t, "java.lang.String");
  }

  private Types types() {
    return ctx.env().types();
  }
}
