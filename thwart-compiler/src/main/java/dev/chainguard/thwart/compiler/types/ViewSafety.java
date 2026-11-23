package dev.chainguard.thwart.compiler.types;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.CompilerEnv;
import dev.chainguard.thwart.compiler.forms.FormPaths;
import dev.chainguard.thwart.runtime.LoopStatus;
import dev.chainguard.thwart.runtime.CssValue;
import dev.chainguard.thwart.runtime.SafeHtml;
import dev.chainguard.thwart.runtime.SafeInlineHtml;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * The view-safe allow-list and the never-view-safe deny-list (docs/architecture.md).
 *
 * <p>View-safe: primitives and boxes, String/CharSequence, enums, records, {@code java.time}
 * value types, BigDecimal/BigInteger, UUID, Optional/List/Set/Collection/Iterable/Map/Map.Entry of
 * view-safe types (and public {@code java.util} types that implement one of them, seen as that
 * interface: {@link #containerView}), arrays of view-safe types, {@code LoopStatus}, {@code SafeHtml}, {@code CssValue},
 * and classes annotated {@code @TemplateView}; all of them public, as are any classes they are
 * nested in and their type arguments, so the generated code can name them ({@link #inaccessible}).
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
public final class ViewSafety {
  static final String OBJECT = "java.lang.Object";

  private static final Set<String> NEVER_TYPES =
      Backports.setOf(
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
      Backports.listOf(
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

  /** Boxes, strings, big numbers and UUID ({@link #isJdkValueType}). */
  private static final Set<String> JDK_VALUE_TYPES =
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
          "java.math.BigInteger",
          "java.util.UUID");

  /** Thwart's own view-safe value types. */
  private static final Set<String> THWART_VALUE_TYPES =
      Backports.setOf(
          LoopStatus.class.getName(),
          SafeHtml.class.getName(),
          SafeInlineHtml.class.getName(),
          CssValue.class.getName());

  /**
   * Whether {@code qualifiedName} is a box, {@code String}, {@code CharSequence},
   * {@code BigDecimal}, {@code BigInteger} or {@code UUID}: a view-safe value that
   * {@code Escapers.json} writes as a JSON scalar (a UUID as a string). The script serializer
   * ({@code ScriptJson}) shares this list.
   */
  public static boolean isJdkValueType(String qualifiedName) {
    return JDK_VALUE_TYPES.contains(qualifiedName);
  }

  /** Container types; view-safe when every type argument is. */
  private static final Set<String> CONTAINERS =
      Backports.setOf(
          "java.util.Optional",
          "java.lang.Iterable",
          "java.util.Collection",
          "java.util.List",
          "java.util.Set",
          "java.util.Map",
          "java.util.Map.Entry");

  /**
   * The interfaces a public {@code java.util} type is seen as ({@link #containerView}), most
   * specific first. Not Iterable: {@code ServiceLoader} is one, and iterating it loads classes.
   */
  private static final List<String> CONTAINER_VIEWS =
      Backports.listOf("java.util.List", "java.util.Set", "java.util.Map", "java.util.Map.Entry", "java.util.Collection");

  /**
   * {@code java.time} types that are not values: Clock/InstantSource read ambient state, and
   * DateTimeException is a Throwable.
   */
  private static final Set<String> TIME_EXCLUDED =
      Backports.setOf("java.time.Clock", "java.time.InstantSource", "java.time.DateTimeException");

  private static final String TEMPLATE_VIEW = "dev.chainguard.thwart.runtime.TemplateView";

  private final CompilerEnv env;

  ViewSafety(CompilerEnv env) {
    this.env = env;
  }

  boolean isViewSafe(TypeMirror type) {
    return !neverViewSafeReason(type).isPresent() && allowed(type) && inaccessible(type) == null;
  }

  /**
   * Whether a view-safe {@code type} may be printed with {@code toString()}: no record or
   * {@code @TemplateView} class in it (itself, an array component, a wildcard bound or a type
   * argument). Their {@code toString()} is user code, and a record's prints every component.
   */
  boolean isText(TypeMirror type) {
    switch (type.getKind()) {
      case ARRAY:
        return isText(((ArrayType) type).getComponentType());
      case WILDCARD: {
        TypeMirror bound = ((WildcardType) type).getExtendsBound();
        return bound == null || isText(bound);
      }
      case DECLARED: {
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        return !isRecord(te) && !hasTemplateView(te) && dt.getTypeArguments().stream().allMatch(this::isText);
      }
      default:
        return true;
    }
  }

  /**
   * The first class in {@code type} (itself, an array component, a wildcard bound or a type
   * argument, depth-first) that generated code can't name because it, or a class it is nested in,
   * is not public; or null. Generated template classes are in their own packages, so
   * package-private access never works for them.
   */
  String inaccessible(TypeMirror type) {
    switch (type.getKind()) {
      case ARRAY:
        return inaccessible(((ArrayType) type).getComponentType());
      case WILDCARD: {
        WildcardType w = (WildcardType) type;
        TypeMirror bound = w.getExtendsBound() != null ? w.getExtendsBound() : w.getSuperBound();
        return bound == null ? null : inaccessible(bound);
      }
      case DECLARED: {
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        if (!isPublic(te)) {
          return te.getQualifiedName().toString();
        }
        for (TypeMirror arg : dt.getTypeArguments()) {
          String hidden = inaccessible(arg);
          if (hidden != null) {
            return hidden;
          }
        }
        return null;
      }
      default:
        return null;
    }
  }

  /**
   * {@code "com.example.Hidden is not public: make it ..."} if the only thing keeping {@code type}
   * from being view-safe is a class in it that generated code can't name, else empty.
   */
  Optional<String> accessReason(TypeMirror type) {
    if (!allowed(type) || neverViewSafeReason(type).isPresent()) {
      return Optional.empty();
    }
    String hidden = inaccessible(type);
    return hidden == null ? Optional.empty() : Optional.of(notPublic(hidden));
  }

  /** Why the generated code can't name the class {@code name}, and the fix. */
  static String notPublic(String name) {
    return name + " is not public: make it public, and any class it is nested in (templates compile to classes in"
        + " their own packages)";
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
          // A plain Field parameter is Field<?>: a field of unknown type, not an Object value.
          boolean fieldOfUnknownType = name.equals(FormPaths.FIELD) && arg.getKind() == TypeKind.WILDCARD;
          Optional<String> r = fieldOfUnknownType ? Optional.empty() : reason(arg, allowObject);
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

  /**
   * {@code type} seen as the container interface it implements, if it is a public {@code java.util}
   * type other than those interfaces: {@code ArrayList<E>} and {@code LinkedList<E>} as
   * {@code List<E>}, {@code TreeSet<E>} as {@code Set<E>}, {@code TreeMap<K, V>} and
   * {@code SortedMap<K, V>} as {@code Map<K, V>}, {@code ArrayDeque<E>} as {@code Collection<E>}.
   * Templates then see only the interface's members, never the class's own methods (such as
   * {@code ArrayDeque.getFirst()}). Otherwise {@code type} itself.
   */
  TypeMirror containerView(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return type;
    }
    TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
    if (CONTAINERS.contains(te.getQualifiedName().toString())
        || !isPublic(te)
        || !env.elements().getPackageOf(te).getQualifiedName().contentEquals("java.util")) {
      return type;
    }
    for (String view : CONTAINER_VIEWS) {
      TypeMirror s = supertype(type, view);
      if (s != null) {
        return s;
      }
    }
    return type;
  }

  /** {@code type}'s supertype named {@code name}, with its type arguments, or null. */
  TypeMirror supertype(TypeMirror type, String name) {
    Deque<TypeMirror> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(type);
    while (!work.isEmpty()) {
      TypeMirror t = work.poll();
      if (t.getKind() != TypeKind.DECLARED || !seen.add(t.toString())) {
        continue;
      }
      if (((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals(name)) {
        return t;
      }
      work.addAll(env.types().directSupertypes(t));
    }
    return null;
  }

  /**
   * Why the parameterized container {@code type} (e.g. {@code Map.Entry<K, V>}) is not view-safe,
   * naming the first type argument that isn't (searched depth-first through nested containers and
   * arrays): {@code "its type argument com.example.Hidden is not public, not a record and not
   * annotated @TemplateView"}. Empty if {@code type} is not a container or all its arguments are
   * view-safe (then the container itself is the problem, e.g. it is raw).
   */
  Optional<String> badTypeArgument(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return Optional.empty();
    }
    DeclaredType dt = (DeclaredType) containerView(type);
    if (!CONTAINERS.contains(((TypeElement) dt.asElement()).getQualifiedName().toString())) {
      return Optional.empty();
    }
    for (TypeMirror arg : dt.getTypeArguments()) {
      String why = whyNotAllowed(arg);
      if (why != null) {
        return Optional.of("its type argument " + why);
      }
    }
    return Optional.empty();
  }

  /** {@code "<innermost offending type> <why>"}, or null if {@code type} is view-safe. */
  private String whyNotAllowed(TypeMirror type) {
    if (allowed(type) && !neverViewSafeReason(type).isPresent()) {
      String hidden = inaccessible(type);
      return hidden == null ? null : notPublic(hidden);
    }
    switch (type.getKind()) {
      case ARRAY:
        return whyNotAllowed(((ArrayType) type).getComponentType());
      case WILDCARD: {
        WildcardType w = (WildcardType) type;
        if (w.getExtendsBound() != null) {
          return whyNotAllowed(w.getExtendsBound());
        }
        return w.getSuperBound() != null
            ? type + " is a ? super wildcard: use a specific type"
            : "? is unbounded: use a specific type";
      }
      case TYPEVAR:
        return type + " is a type variable: use a specific type";
      case DECLARED: {
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        Optional<String> never = neverViewSafeReason(type);
        if (never.isPresent() && deniedSelfOrSupertype(dt) != null) {
          return never.get(); // the type itself is deny-listed
        }
        DeclaredType view = (DeclaredType) containerView(dt);
        if (CONTAINERS.contains(((TypeElement) view.asElement()).getQualifiedName().toString())) {
          if (view.getTypeArguments().isEmpty()) {
            return type + " is raw: declare its type arguments";
          }
          for (TypeMirror arg : view.getTypeArguments()) {
            String why = whyNotAllowed(arg);
            if (why != null) {
              return why;
            }
          }
        }
        if (never.isPresent()) {
          return never.get();
        }
        return type
            + (isPublic(te)
                ? " is not a record or annotated @TemplateView"
                : " is not public, not a record and not annotated @TemplateView");
      }
      default:
        return type + " is not view-safe";
    }
  }

  /** Whether {@code te} and every type enclosing it are public (accessible from generated code). */
  private static boolean isPublic(TypeElement te) {
    for (Element e = te; e instanceof TypeElement; e = e.getEnclosingElement()) {
      if (!e.getModifiers().contains(Modifier.PUBLIC)) {
        return false;
      }
    }
    return true;
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
    if (te.getKind() == ElementKind.ENUM || isRecord(te)) {
      return true;
    }
    if (JDK_VALUE_TYPES.contains(name) || THWART_VALUE_TYPES.contains(name) || hasTemplateView(te)) {
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
    TypeMirror view = containerView(type);
    return view != type && allowed(view);
  }

  /**
   * Whether {@code te} is a record. By kind name: the processor is Java 8 code and
   * {@code ElementKind.RECORD} exists only on JDK 16+, where javac can compile records.
   */
  static boolean isRecord(TypeElement te) {
    return te.getKind().name().equals("RECORD");
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
