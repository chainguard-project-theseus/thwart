package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.types.CompiledExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The value {@code [[...]]} and {@code t:text} pass to {@code Escapers.json} inside
 * {@code <script>}.
 *
 * <p>Strings, numbers, booleans, enums, {@code java.time} values, and Optionals, collections,
 * arrays and String-keyed maps of those go to {@code Escapers.json} as they are. Records and
 * {@code @TemplateView} classes (#121) are converted first by static helper methods generated
 * into the template class: each copies the bean's properties into a {@code LinkedHashMap}, which
 * {@code Escapers.json} then serializes (and escapes) like any other map. The properties are
 * the record components and the public no-arg {@code getX()} (and boolean {@code isX()})
 * getters, the members templates can read, named as Jackson (Thymeleaf's serializer) names
 * them and in its order where that is defined: record components, else properties backed by a
 * field in field order, then getter-only properties (in declaration order here; Jackson's order
 * for those depends on reflection). A getter whose return type is never view-safe is left out; any
 * other property type that cannot be serialized is a build error. There is no reflection: the
 * set of properties is fixed at build time by the static type.
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

  /** Boxes and JDK value types {@code Escapers.json} serializes as a JSON scalar. */
  private static final Set<String> JSON_VALUES =
      Backports.setOf(
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double",
          "java.lang.Character",
          "java.lang.String",
          "java.lang.CharSequence",
          "java.math.BigDecimal",
          "java.math.BigInteger");

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
   * The Java expression to pass to {@code Escapers.json} for {@code c}, or null after reporting
   * an error at its position.
   */
  static String value(CodegenContext ctx, CompiledExpr c) {
    ScriptJson json = new ScriptJson(ctx);
    String code = json.convert(c.type(), c.code(), "0");
    if (code == null) {
      ctx.diagnostics()
          .error(
              c.pos(),
              "cannot output a value of type " + c.type() + " inside <script>: only strings, numbers,"
                  + " booleans, enums, java.time values, records and @TemplateView classes of those, and"
                  + " Optionals, collections, arrays and String-keyed maps of those can be serialized as JSON"
                  + (json.failure == null ? "" : "; " + json.failure));
    }
    return code;
  }

  /**
   * An expression converting {@code code} (static type {@code t}) into a value
   * {@code Escapers.json} can serialize ({@code code} itself if no bean is involved), or null if
   * {@code t} cannot be serialized. {@code depth} is the nesting depth expression.
   */
  private String convert(TypeMirror t, String code, String depth) {
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
        return bound == null ? null : convert(bound, code, depth);
      }
      case TYPEVAR:
        return convert(types().erasure(t), code, depth);
      case ARRAY: {
        TypeMirror component = ((ArrayType) t).getComponentType();
        if (component.getKind().isPrimitive()) {
          return code;
        }
        String item = convert(component, "e$", "d$ + 1");
        if (item == null) {
          return null;
        }
        return item.equals("e$")
            ? code
            : container(t, code, depth, "java.util.List<java.lang.Object> l$ = new java.util.ArrayList<>();\n"
                + "for (java.lang.Object e$ : (java.lang.Object[]) o$) { l$.add(" + item + "); }\nreturn l$;\n");
      }
      case DECLARED:
        break;
      default:
        return null;
    }
    DeclaredType dt = (DeclaredType) t;
    TypeElement te = (TypeElement) dt.asElement();
    String name = te.getQualifiedName().toString();
    if (te.getKind() == ElementKind.ENUM || JSON_VALUES.contains(name) || JSON_TIME.contains(name)) {
      return code;
    }
    List<? extends TypeMirror> args = dt.getTypeArguments();
    if (name.equals("java.util.Optional") || JSON_COLLECTIONS.contains(name)) {
      String item = args.size() == 1 ? convert(args.get(0), "e$", "d$ + 1") : null;
      if (item == null) {
        return null;
      }
      if (item.equals("e$")) {
        return code;
      }
      return name.equals("java.util.Optional")
          ? container(t, code, depth, "java.lang.Object e$ = ((java.util.Optional<?>) o$).orElse(null);\n"
              + "return " + item + ";\n")
          : container(t, code, depth, "java.util.List<java.lang.Object> l$ = new java.util.ArrayList<>();\n"
              + "for (java.lang.Object e$ : (java.util.Collection<?>) o$) { l$.add(" + item + "); }\nreturn l$;\n");
    }
    if (name.equals("java.util.Map")) {
      String item = args.size() == 2 && isString(args.get(0)) ? convert(args.get(1), "e$", "d$ + 1") : null;
      if (item == null) {
        return null;
      }
      // Keys are copied as they are, so Escapers.json still rejects a non-String key.
      return item.equals("e$")
          ? code
          : container(t, code, depth, "java.util.Map<java.lang.Object, java.lang.Object> m$ = new java.util.LinkedHashMap<>();\n"
              + "for (java.util.Map.Entry<?, ?> x$ : ((java.util.Map<?, ?>) o$).entrySet()) {\n"
              + "java.lang.Object e$ = x$.getValue();\nm$.put(x$.getKey(), " + item + ");\n}\nreturn m$;\n");
    }
    if (isBean(te) && !ctx.expressions().neverViewSafeReason(t).isPresent()) {
      return bean(dt, code, depth);
    }
    return null;
  }

  /** A call of the helper converting container type {@code t} with {@code body}. */
  private String container(TypeMirror t, String code, String depth, String body) {
    String key = t.toString();
    String helper = helpers.get(key);
    if (helper == null) {
      helper = ctx.freshName("json");
      helpers.put(key, helper);
      addHelper(helper, body);
    }
    return helper + "(" + code + ", " + depth + ")";
  }

  /** A call of the helper converting bean {@code t} to a map of its properties, or null. */
  private String bean(DeclaredType t, String code, String depth) {
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
      String erased = types().erasure(t).toString();
      StringBuilder body = new StringBuilder();
      body.append(erased).append(" v$ = (").append(erased).append(") o$;\n");
      body.append("java.util.Map<java.lang.String, java.lang.Object> m$ = new java.util.LinkedHashMap<>();\n");
      for (Map.Entry<String, ExecutableElement> p : properties(te).entrySet()) {
        ExecutableElement getter = p.getValue();
        TypeMirror type = ((ExecutableType) types().asMemberOf(t, getter)).getReturnType();
        if (ctx.expressions().neverViewSafeReason(type).isPresent()) {
          continue; // never readable from a template, so never serialized either
        }
        String value = convert(type, "v$." + getter.getSimpleName() + "()", "d$ + 1");
        if (value == null) {
          if (failure == null) {
            failure = "property '" + p.getKey() + "' of " + t + " has type " + type;
          }
          return null;
        }
        body.append("m$.put(").append(JavaStrings.literal(p.getKey())).append(", ").append(value).append(");\n");
      }
      body.append("return m$;\n");
      generating.remove(element);
      addHelper(helper, body.toString());
    }
    return helper + "(" + code + ", " + depth + ")";
  }

  private void addHelper(String name, String body) {
    ctx.addMember(
        "private static java.lang.Object " + name + "(java.lang.Object o$, int d$) {\n"
            + "if (o$ == null) { return null; }\n"
            + "if (d$ > " + MAX_DEPTH + ") { throw new java.lang.IllegalArgumentException(\"value nested deeper"
            + " than " + MAX_DEPTH + " (cycle?)\"); }\n"
            + body
            + "}\n");
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
        && isBoolean(m.getReturnType())) {
      rest = n.substring(2);
    } else {
      return null;
    }
    if (n.equals("getClass")) {
      return null;
    }
    StringBuilder sb = new StringBuilder(rest);
    for (int i = 0; i < sb.length() && Character.isUpperCase(sb.charAt(i)); i++) {
      sb.setCharAt(i, Character.toLowerCase(sb.charAt(i)));
    }
    return sb.toString();
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN
        || (t.getKind() == TypeKind.DECLARED
            && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals("java.lang.Boolean"));
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
    return t.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals("java.lang.String");
  }

  private Types types() {
    return ctx.env().types();
  }
}
