package dev.tessera.compiler.types;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.TypeRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves declared parameter type names ({@code t:params}, fragment parameters) to javac types:
 * primitives, {@code t:import} entries (single-type and wildcard, with ambiguity errors), the
 * implicit packages, qualified and nested names, type arguments and array dimensions. A
 * never-view-safe class is rejected before its type arguments are looked at. Errors are reported
 * at the reference's position and {@code null} is returned.
 */
final class TypeResolver {
  private static final List<String> IMPLICIT_PACKAGES =
      Backports.listOf("java.lang", "java.util", "java.time", "java.math", "dev.tessera.runtime");
  private static final Map<String, TypeKind> PRIMITIVES = new HashMap<>();

  static {
    for (TypeKind k : TypeKind.values()) {
      if (k.isPrimitive()) {
        PRIMITIVES.put(k.name().toLowerCase(Locale.ROOT), k);
      }
    }
  }

  private final CompilerEnv env;
  private final ViewSafety safety;

  TypeResolver(CompilerEnv env, ViewSafety safety) {
    this.env = env;
    this.safety = safety;
  }

  /**
   * The type {@code ref} names, resolving simple names against {@code imports} ({@code t:import}
   * entries) and then the implicit packages; null if it cannot be resolved (reported).
   */
  TypeMirror resolve(TypeRef ref, List<String> imports) {
    TypeMirror base;
    TypeKind pk = PRIMITIVES.get(ref.name());
    if (pk != null) {
      if (!ref.typeArgs().isEmpty()) {
        return error(ref.pos(), ref.name() + " cannot have type arguments");
      }
      base = env.types().getPrimitiveType(pk);
    } else {
      TypeElement te = resolveName(ref.name(), imports, ref.pos());
      if (te == null) {
        return null;
      }
      Optional<String> never = safety.neverViewSafeReason(env.types().erasure(te.asType()));
      if (never.isPresent()) {
        return error(ref.pos(), "parameter type " + te.getQualifiedName() + " is not allowed: " + never.get());
      }
      int expected = te.getTypeParameters().size();
      int got = ref.typeArgs().size();
      if (got == 0 && expected > 0) {
        return error(ref.pos(), ref.name() + " needs " + expected + " type argument(s), e.g. " + ref.name() + "<String>");
      }
      if (got != expected) {
        return error(ref.pos(), te.getQualifiedName() + " takes " + expected + " type argument(s), got " + got);
      }
      TypeMirror[] args = new TypeMirror[got];
      for (int i = 0; i < got; i++) {
        TypeRef a = ref.typeArgs().get(i);
        args[i] = resolve(a, imports);
        if (args[i] == null) {
          return null;
        }
        if (args[i].getKind().isPrimitive()) {
          return error(a.pos(), "type arguments must be reference types: use " + env.types().boxedClass((PrimitiveType) args[i]).asType() + ", not " + a.name());
        }
      }
      base = env.types().getDeclaredType(te, args);
    }
    for (int d = 0; d < ref.arrayDims(); d++) {
      base = env.types().getArrayType(base);
    }
    return base;
  }

  private TypeElement resolveName(String name, List<String> imports, SourcePos pos) {
    if (name.contains(".")) {
      TypeElement te = env.elements().getTypeElement(name);
      if (te != null) {
        return te;
      }
      int dot = name.indexOf('.');
      TypeElement outer = resolveSimple(name.substring(0, dot), imports, pos, false);
      if (outer != null) {
        te = env.elements().getTypeElement(outer.getQualifiedName() + name.substring(dot));
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
      String imp = Backports.strip(raw);
      if (!imp.endsWith(".*") && (imp.equals(simple) || imp.endsWith("." + simple))) {
        TypeElement te = env.elements().getTypeElement(imp);
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
      String imp = Backports.strip(raw);
      if (imp.endsWith(".*")) {
        TypeElement te = env.elements().getTypeElement(imp.substring(0, imp.length() - 1) + simple);
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
      TypeElement te = env.elements().getTypeElement(pkg + "." + simple);
      if (te != null) {
        return te;
      }
    }
    if (report) {
      env.diagnostics().error(pos, "unknown type '" + simple + "': add it to t:import or use its qualified name");
    }
    return null;
  }

  private <T> T error(SourcePos pos, String message) {
    env.diagnostics().error(pos, message);
    return null;
  }
}
