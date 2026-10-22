package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.runtime.LoopStatus;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.TrustedUrl;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * The view-safe allow-list and the never-view-safe deny-list (docs/mvp-design.md).
 *
 * <p>View-safe: primitives and boxes, String/CharSequence, enums, records, {@code java.time}
 * value types, BigDecimal/BigInteger, Optional/List/Set/Collection/Iterable/Map/Map.Entry of
 * view-safe types, arrays of view-safe types, {@code LoopStatus}, {@code SafeHtml}, {@code TrustedUrl},
 * and classes annotated {@code @TemplateView}.
 *
 * <p>Never view-safe, even if annotated or registered: {@code Object} (as a declared type and
 * its methods), {@code Class}, {@code ClassLoader}, {@code java.lang.reflect.*},
 * {@code java.lang.invoke.*}, {@code Thread}, {@code ThreadGroup}, {@code Runtime},
 * {@code ProcessBuilder}, {@code Process}, {@code ProcessHandle}, {@code System},
 * {@code SecurityManager}, {@code Module}, {@code ModuleLayer},
 * {@code java.io.File}, {@code java.nio.file.*}, {@code javax.script.*}, {@code jakarta.servlet.*},
 * {@code javax.servlet.*}, {@code org.springframework.context.*},
 * {@code org.springframework.beans.*}, {@code org.springframework.core.env.*},
 * {@code org.springframework.core.io.*}, {@code org.springframework.web.context.*}, and
 * {@code org.springframework.expression.*}. A type is also never view-safe if any of its
 * supertypes (other than {@code Object}) or type arguments is. {@code Object} is allowed only as a
 * {@code @TemplateFunction} parameter type (input only: nothing can be called on it).
 */
final class ViewSafety {
  static final String OBJECT = "java.lang.Object";

  private static final Set<String> NEVER_TYPES =
      Set.of(
          OBJECT,
          "java.lang.Class",
          "java.lang.ClassLoader",
          "java.lang.Thread",
          "java.lang.ThreadGroup",
          "java.lang.Runtime",
          "java.lang.ProcessBuilder",
          "java.lang.Process",
          "java.lang.ProcessHandle",
          "java.lang.System",
          "java.lang.SecurityManager",
          "java.lang.Module",
          "java.lang.ModuleLayer",
          "java.io.File");

  private static final List<String> NEVER_PACKAGES =
      List.of(
          "java.lang.reflect",
          "java.lang.invoke",
          "java.nio.file",
          "javax.script",
          "jakarta.servlet",
          "javax.servlet",
          "org.springframework.context",
          "org.springframework.beans",
          "org.springframework.core.env",
          "org.springframework.core.io",
          "org.springframework.web.context",
          "org.springframework.expression");

  private static final Set<String> VALUE_TYPES =
      Set.of(
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
          "java.math.BigInteger",
          LoopStatus.class.getName(),
          SafeHtml.class.getName(),
          TrustedUrl.class.getName());

  /** Container types; view-safe when every type argument is. */
  private static final Set<String> CONTAINERS =
      Set.of(
          "java.util.Optional",
          "java.lang.Iterable",
          "java.util.Collection",
          "java.util.List",
          "java.util.Set",
          "java.util.Map",
          "java.util.Map.Entry");

  /**
   * {@code java.time} types that are not values: Clock/InstantSource read ambient state, and
   * DateTimeException is a Throwable.
   */
  private static final Set<String> TIME_EXCLUDED =
      Set.of("java.time.Clock", "java.time.InstantSource", "java.time.DateTimeException");

  private static final String TEMPLATE_VIEW = "dev.tessera.runtime.TemplateView";

  private final CompilerEnv env;

  ViewSafety(CompilerEnv env) {
    this.env = env;
  }

  boolean isViewSafe(TypeMirror type) {
    return neverViewSafeReason(type).isEmpty() && allowed(type);
  }

  /** Why {@code type} is never view-safe, or empty if it is not on the deny-list. */
  Optional<String> neverViewSafeReason(TypeMirror type) {
    return reason(type, false);
  }

  /**
   * Like {@link #neverViewSafeReason}, but with {@code allowObject} {@code Object} (and unbounded
   * wildcards / type variables) is accepted anywhere: used for function parameter types.
   */
  Optional<String> reason(TypeMirror type, boolean allowObject) {
    switch (type.getKind()) {
      case ARRAY:
        return reason(((ArrayType) type).getComponentType(), allowObject);
      case WILDCARD: {
        WildcardType w = (WildcardType) type;
        if (w.getExtendsBound() != null) {
          return reason(w.getExtendsBound(), allowObject);
        }
        if (w.getSuperBound() != null) {
          return reason(w.getSuperBound(), allowObject);
        }
        return allowObject ? Optional.empty() : Optional.of(OBJECT + " (unbounded ?) is never view-safe");
      }
      case TYPEVAR:
        // Its erasure is its leftmost bound; don't recurse into bounds (they can be recursive).
        return reason(env.types().erasure(type), allowObject);
      case INTERSECTION: {
        for (TypeMirror b : ((IntersectionType) type).getBounds()) {
          Optional<String> r = reason(b, allowObject);
          if (r.isPresent()) {
            return r;
          }
        }
        return Optional.empty();
      }
      case DECLARED: {
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        String name = te.getQualifiedName().toString();
        if (name.equals(OBJECT)) {
          return allowObject
              ? Optional.empty()
              : Optional.of(OBJECT + " is never view-safe: declare a specific type");
        }
        String denied = deniedSelfOrSupertype(dt);
        if (denied != null) {
          return Optional.of(
              denied.equals(name)
                  ? name + " is never view-safe"
                  : name + " is never view-safe (it is a " + denied + ")");
        }
        for (TypeMirror arg : dt.getTypeArguments()) {
          Optional<String> r = reason(arg, allowObject);
          if (r.isPresent()) {
            return r;
          }
        }
        return Optional.empty();
      }
      default:
        return Optional.empty();
    }
  }

  /** The first deny-listed name among {@code type} and its supertypes (Object excluded), or null. */
  private String deniedSelfOrSupertype(DeclaredType type) {
    Deque<TypeMirror> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(type);
    while (!work.isEmpty()) {
      TypeMirror t = work.poll();
      if (t.getKind() != TypeKind.DECLARED) {
        continue;
      }
      TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
      String name = te.getQualifiedName().toString();
      if (!seen.add(name) || name.equals(OBJECT)) {
        continue;
      }
      if (NEVER_TYPES.contains(name) || inDeniedPackage(te)) {
        return name;
      }
      work.addAll(env.types().directSupertypes(t));
    }
    return null;
  }

  private boolean inDeniedPackage(TypeElement te) {
    String pkg = env.elements().getPackageOf(te).getQualifiedName().toString();
    for (String p : NEVER_PACKAGES) {
      if (pkg.equals(p) || pkg.startsWith(p + ".")) {
        return true;
      }
    }
    return false;
  }

  private boolean allowed(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive() || kind == TypeKind.NULL) {
      return true;
    }
    if (kind == TypeKind.ARRAY) {
      return allowed(((ArrayType) type).getComponentType());
    }
    if (kind == TypeKind.WILDCARD) {
      TypeMirror bound = ((WildcardType) type).getExtendsBound();
      return bound != null && allowed(bound);
    }
    if (kind != TypeKind.DECLARED) {
      return false;
    }
    DeclaredType dt = (DeclaredType) type;
    TypeElement te = (TypeElement) dt.asElement();
    String name = te.getQualifiedName().toString();
    if (te.getKind() == ElementKind.ENUM || te.getKind() == ElementKind.RECORD) {
      return true;
    }
    if (VALUE_TYPES.contains(name) || hasTemplateView(te)) {
      return true;
    }
    if (env.elements().getPackageOf(te).getQualifiedName().contentEquals("java.time")) {
      return !TIME_EXCLUDED.contains(name);
    }
    if (CONTAINERS.contains(name)) {
      List<? extends TypeMirror> args = dt.getTypeArguments();
      if (args.isEmpty()) {
        return false; // raw: element type unknown
      }
      for (TypeMirror a : args) {
        if (!allowed(a)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean hasTemplateView(TypeElement te) {
    return annotation(te, TEMPLATE_VIEW) != null;
  }

  /** The annotation of type {@code qualifiedName} directly present on {@code e}, or null. */
  static AnnotationMirror annotation(Element e, String qualifiedName) {
    for (AnnotationMirror am : e.getAnnotationMirrors()) {
      TypeElement at = (TypeElement) am.getAnnotationType().asElement();
      if (at.getQualifiedName().contentEquals(qualifiedName)) {
        return am;
      }
    }
    return null;
  }
}
