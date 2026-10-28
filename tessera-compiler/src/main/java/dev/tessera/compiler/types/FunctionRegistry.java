package dev.tessera.compiler.types;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.SourcePos;
import dev.tessera.runtime.TemplateFunctions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Registered template functions: {@code public static} methods annotated
 * {@code @TemplateFunction} in classes annotated {@code @TemplateFunctions("ns")}, taken from
 * {@link CompilerEnv#functionNamespaces()} plus every {@link BuiltinNamespaces} class present on
 * the classpath. Any other namespace is looked up on first use in the function index
 * ({@link CompilerEnv#functionIndex()}: classes compiled earlier, #118); the indexed class must
 * still be annotated {@code @TemplateFunctions} with that namespace and goes through the same
 * checks. Duplicate namespaces, non-static or non-public methods, and signatures using
 * never-view-safe types are build errors (reported once, when the class is registered, at
 * {@code path/To/Class.java:1:1}). A leading {@code java.util.Locale} parameter is not a template
 * argument: the compiler passes the render locale ({@code ctx.locale()}) to it ({@link
 * #takesLocale}).
 */
final class FunctionRegistry {
  static final String RESERVED = "fields";
  /** The reserved {@code ids} namespace ({@code IdsCompiler}, Thymeleaf's {@code #ids}). */
  static final String IDS = "ids";
  private static final String FUNCTIONS = TemplateFunctions.class.getName();
  private static final String FUNCTION = "dev.tessera.runtime.TemplateFunction";

  private final CompilerEnv env;
  private final ViewSafety safety;
  private final Map<String, TypeElement> classes = new LinkedHashMap<>();
  private final Map<String, List<ExecutableElement>> functions = new LinkedHashMap<>();
  private final Set<String> looked = new HashSet<>();

  FunctionRegistry(CompilerEnv env) {
    this.env = env;
    this.safety = new ViewSafety(env);
    Map<String, TypeElement> candidates = new LinkedHashMap<>();
    for (TypeElement te : env.functionNamespaces()) {
      candidates.putIfAbsent(te.getQualifiedName().toString(), te);
    }
    for (String name : BuiltinNamespaces.CLASSES) {
      TypeElement te = env.elements().getTypeElement(name);
      if (te != null) {
        candidates.putIfAbsent(name, te);
      }
    }
    candidates.values().forEach(this::register);
  }

  /** Namespaces registered so far (indexed ones only once looked up). */
  Set<String> namespaces() {
    return Collections.unmodifiableSet(classes.keySet());
  }

  /** Whether {@code namespace} is registered, consulting the function index on first use. */
  boolean isNamespace(String namespace) {
    return namespaceClass(namespace).isPresent();
  }

  /** The class implementing {@code namespace}, if registered. */
  Optional<TypeElement> namespaceClass(String namespace) {
    resolve(namespace);
    return Optional.ofNullable(classes.get(namespace));
  }

  /** All registered overloads of {@code ns.name} (empty if there is no such function). */
  List<ExecutableElement> overloads(String namespace, String name) {
    resolve(namespace);
    return functions.getOrDefault(namespace, Backports.listOf()).stream()
        .filter(m -> m.getSimpleName().contentEquals(name))
        .collect(Collectors.toList());
  }

  /** The unique applicable overload of {@code ns.name} for {@code argTypes}, if any. */
  Optional<ExecutableElement> lookup(String namespace, String name, List<TypeMirror> argTypes) {
    List<ExecutableElement> overloads = overloads(namespace, name);
    // JLS 15.12.2 phases: subtyping only, then boxing, then varargs.
    for (int phase = 1; phase <= 3; phase++) {
      List<ExecutableElement> applicable = new ArrayList<>();
      for (ExecutableElement m : overloads) {
        if (applicable(m, argTypes, phase)) {
          applicable.add(m);
        }
      }
      if (applicable.size() == 1) {
        return Optional.of(applicable.get(0));
      }
      if (applicable.size() > 1) {
        return mostSpecific(applicable);
      }
    }
    return Optional.empty();
  }

  /**
   * Registers the indexed class for {@code ns}, once, if there is one and it still declares
   * {@code @TemplateFunctions(ns)}. Stale or forged entries are ignored, so the namespace stays
   * unknown.
   */
  private void resolve(String ns) {
    if (!looked.add(ns)) { // registered, rejected, or already looked up
      return;
    }
    String className = env.functionIndex().apply(ns);
    TypeElement te = className == null ? null : env.elements().getTypeElement(className);
    if (te != null && ns.equals(namespaceOf(te))) {
      register(te);
    }
  }

  private void register(TypeElement te) {
    String ns = namespaceOf(te);
    if (ns == null) {
      return;
    }
    looked.add(ns); // the index never overrides a class seen here, even a rejected one
    SourcePos pos = positionOf(te);
    String className = te.getQualifiedName().toString();
    if (!SourceVersion.isIdentifier(ns) || SourceVersion.isKeyword(ns)) {
      env.diagnostics().error(pos, "invalid function namespace '" + ns + "' on " + className + ": must be a Java identifier");
      return;
    }
    if (ns.equals(RESERVED)) {
      env.diagnostics().error(pos, "function namespace '" + RESERVED + "' on " + className + " is reserved for form errors");
      return;
    }
    if (ns.equals(IDS)) {
      env.diagnostics().error(pos, "function namespace '" + IDS + "' on " + className + " is reserved for element ids");
      return;
    }
    TypeElement existing = classes.get(ns);
    if (existing != null) {
      env.diagnostics()
          .error(pos, "duplicate function namespace '" + ns + "': declared by " + existing.getQualifiedName() + " and " + className);
      return;
    }
    if (!te.getModifiers().contains(Modifier.PUBLIC)) {
      env.diagnostics().error(pos, "@TemplateFunctions class " + className + " must be public");
      return;
    }
    List<ExecutableElement> valid = new ArrayList<>();
    for (ExecutableElement m : ElementFilter.methodsIn(te.getEnclosedElements())) {
      if (ViewSafety.annotation(m, FUNCTION) != null && validFunction(m, className + "." + m.getSimpleName(), pos)) {
        valid.add(m);
      }
    }
    classes.put(ns, te);
    functions.put(ns, valid);
  }

  /**
   * Checks a {@code @TemplateFunction} method, reporting at {@code pos}; a bad return type and every
   * bad parameter are all reported before returning.
   */
  private boolean validFunction(ExecutableElement m, String fn, SourcePos pos) {
    if (!m.getModifiers().contains(Modifier.PUBLIC) || !m.getModifiers().contains(Modifier.STATIC)) {
      env.diagnostics().error(pos, "@TemplateFunction " + fn + " must be public static");
      return false;
    }
    if (m.getReturnType().getKind() == TypeKind.VOID) {
      env.diagnostics().error(pos, "@TemplateFunction " + fn + " must return a value");
      return false;
    }
    boolean ok = true;
    Optional<String> r = safety.neverViewSafeReason(m.getReturnType());
    if (r.isPresent()) {
      env.diagnostics().error(pos, "@TemplateFunction " + fn + " returns " + m.getReturnType() + ": " + r.get());
      ok = false;
    }
    for (VariableElement p : templateParameters(m)) {
      Optional<String> pr = safety.reason(p.asType(), true);
      if (pr.isPresent()) {
        env.diagnostics()
            .error(pos, "@TemplateFunction " + fn + " parameter " + p.getSimpleName() + " has type " + p.asType() + ": " + pr.get());
        ok = false;
      }
    }
    return ok;
  }

  private boolean applicable(ExecutableElement m, List<TypeMirror> args, int phase) {
    List<TypeMirror> params = paramTypes(m);
    if (phase < 3) {
      if (params.size() != args.size()) {
        return false;
      }
      for (int i = 0; i < args.size(); i++) {
        if (!convertible(args.get(i), params.get(i), phase == 1)) {
          return false;
        }
      }
      return true;
    }
    if (!m.isVarArgs()) {
      return false;
    }
    int fixed = params.size() - 1;
    if (args.size() < fixed) {
      return false;
    }
    TypeMirror component = ((ArrayType) params.get(fixed)).getComponentType();
    for (int i = 0; i < args.size(); i++) {
      if (!convertible(args.get(i), i < fixed ? params.get(i) : component, false)) {
        return false;
      }
    }
    return true;
  }

  private boolean convertible(TypeMirror arg, TypeMirror param, boolean strict) {
    Types types = env.types();
    return strict ? types.isSubtype(arg, param) : types.isAssignable(arg, param);
  }

  private Optional<ExecutableElement> mostSpecific(List<ExecutableElement> applicable) {
    ExecutableElement best = null;
    for (ExecutableElement m : applicable) {
      boolean beatsAll = true;
      for (ExecutableElement o : applicable) {
        if (o != m && !moreSpecific(m, o)) {
          beatsAll = false;
          break;
        }
      }
      if (beatsAll) {
        if (best != null) {
          return Optional.empty();
        }
        best = m;
      }
    }
    return Optional.ofNullable(best);
  }

  private boolean moreSpecific(ExecutableElement m, ExecutableElement o) {
    List<TypeMirror> a = paramTypes(m);
    List<TypeMirror> b = paramTypes(o);
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!env.types().isSubtype(a.get(i), b.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code m}'s first parameter is a {@code java.util.Locale}, which receives the render locale. */
  boolean takesLocale(ExecutableElement m) {
    List<? extends VariableElement> params = m.getParameters();
    if (params.isEmpty() || params.get(0).asType().getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element type = env.types().asElement(params.get(0).asType());
    return type instanceof TypeElement && ((TypeElement) type).getQualifiedName().contentEquals("java.util.Locale");
  }

  /** The parameters a template passes arguments to: all but a leading render-locale parameter. */
  private List<? extends VariableElement> templateParameters(ExecutableElement m) {
    List<? extends VariableElement> params = m.getParameters();
    return takesLocale(m) ? params.subList(1, params.size()) : params;
  }

  /** Template parameter types; erased for generic methods (their type variables are not inferred). */
  private List<TypeMirror> paramTypes(ExecutableElement m) {
    boolean generic = !m.getTypeParameters().isEmpty();
    List<TypeMirror> out = new ArrayList<>();
    for (VariableElement p : templateParameters(m)) {
      out.add(generic ? env.types().erasure(p.asType()) : p.asType());
    }
    return out;
  }

  private static String namespaceOf(TypeElement te) {
    AnnotationMirror am = ViewSafety.annotation(te, FUNCTIONS);
    if (am == null) {
      return null;
    }
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
      if (e.getKey().getSimpleName().contentEquals("value") && e.getValue().getValue() instanceof String) {
        return (String) e.getValue().getValue();
      }
    }
    return null;
  }

  /** Function classes have no template position; report against their source file. */
  private static SourcePos positionOf(TypeElement te) {
    Element top = te;
    while (top.getEnclosingElement() instanceof TypeElement) {
      top = top.getEnclosingElement();
    }
    return new SourcePos(((TypeElement) top).getQualifiedName().toString().replace('.', '/') + ".java", 1, 1);
  }
}
