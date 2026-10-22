package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.SourcePos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * the classpath. Duplicate namespaces, non-static or non-public methods, and signatures using
 * never-view-safe types are build errors (reported once, when the registry is built, at
 * {@code path/To/Class.java:1:1}).
 */
public final class FunctionRegistry {
  static final String RESERVED = "fields";
  private static final String FUNCTIONS = "dev.tessera.runtime.TemplateFunctions";
  private static final String FUNCTION = "dev.tessera.runtime.TemplateFunction";

  private final CompilerEnv env;
  private final ViewSafety safety;
  private final Map<String, TypeElement> classes = new LinkedHashMap<>();
  private final Map<String, List<ExecutableElement>> functions = new LinkedHashMap<>();

  public FunctionRegistry(CompilerEnv env) {
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

  public Set<String> namespaces() {
    return Collections.unmodifiableSet(classes.keySet());
  }

  /** The class implementing {@code namespace}, if registered. */
  public Optional<TypeElement> namespaceClass(String namespace) {
    return Optional.ofNullable(classes.get(namespace));
  }

  /** All registered overloads of {@code ns.name} (empty if there is no such function). */
  public List<ExecutableElement> overloads(String namespace, String name) {
    return functions.getOrDefault(namespace, List.of()).stream()
        .filter(m -> m.getSimpleName().contentEquals(name))
        .toList();
  }

  /** The unique applicable overload of {@code ns.name} for {@code argTypes}, if any. */
  public Optional<ExecutableElement> lookup(String namespace, String name, List<TypeMirror> argTypes) {
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

  private void register(TypeElement te) {
    SourcePos pos = positionOf(te);
    String ns = namespaceOf(te);
    if (ns == null) {
      return;
    }
    String className = te.getQualifiedName().toString();
    if (!SourceVersion.isIdentifier(ns) || SourceVersion.isKeyword(ns)) {
      env.diagnostics().error(pos, "invalid function namespace '" + ns + "' on " + className + ": must be a Java identifier");
      return;
    }
    if (ns.equals(RESERVED)) {
      env.diagnostics().error(pos, "function namespace '" + RESERVED + "' on " + className + " is reserved for form errors");
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
      if (!hasAnnotation(m, FUNCTION)) {
        continue;
      }
      String fn = className + "." + m.getSimpleName();
      if (!m.getModifiers().contains(Modifier.PUBLIC) || !m.getModifiers().contains(Modifier.STATIC)) {
        env.diagnostics().error(pos, "@TemplateFunction " + fn + " must be public static");
        continue;
      }
      if (m.getReturnType().getKind() == TypeKind.VOID) {
        env.diagnostics().error(pos, "@TemplateFunction " + fn + " must return a value");
        continue;
      }
      boolean ok = true;
      Optional<String> r = safety.reason(m.getReturnType(), false);
      if (r.isPresent()) {
        env.diagnostics().error(pos, "@TemplateFunction " + fn + " returns " + m.getReturnType() + ": " + r.get());
        ok = false;
      }
      for (VariableElement p : m.getParameters()) {
        Optional<String> pr = safety.reason(p.asType(), true);
        if (pr.isPresent()) {
          env.diagnostics()
              .error(pos, "@TemplateFunction " + fn + " parameter " + p.getSimpleName() + " has type " + p.asType() + ": " + pr.get());
          ok = false;
        }
      }
      if (ok) {
        valid.add(m);
      }
    }
    classes.put(ns, te);
    functions.put(ns, valid);
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

  /** Parameter types; erased for generic methods (their type variables are not inferred). */
  private List<TypeMirror> paramTypes(ExecutableElement m) {
    boolean generic = !m.getTypeParameters().isEmpty();
    List<TypeMirror> out = new ArrayList<>();
    for (VariableElement p : m.getParameters()) {
      out.add(generic ? env.types().erasure(p.asType()) : p.asType());
    }
    return out;
  }

  private static String namespaceOf(TypeElement te) {
    for (AnnotationMirror am : te.getAnnotationMirrors()) {
      if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(FUNCTIONS)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value") && e.getValue().getValue() instanceof String s) {
            return s;
          }
        }
      }
    }
    return null;
  }

  private static boolean hasAnnotation(Element e, String annotation) {
    for (AnnotationMirror am : e.getAnnotationMirrors()) {
      if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
        return true;
      }
    }
    return false;
  }

  /** Function classes have no template position; report against their source file. */
  private static SourcePos positionOf(TypeElement te) {
    Element top = te;
    while (top.getEnclosingElement() instanceof TypeElement outer) {
      top = outer;
    }
    return new SourcePos(((TypeElement) top).getQualifiedName().toString().replace('.', '/') + ".java", 1, 1);
  }
}
