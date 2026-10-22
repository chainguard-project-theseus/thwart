package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Registered template functions: {@code public static} methods annotated
 * {@code @TemplateFunction} in classes annotated {@code @TemplateFunctions("ns")}, taken from
 * {@link CompilerEnv#functionNamespaces()} plus every {@link BuiltinNamespaces} class present on
 * the classpath. Duplicate namespaces, non-static or non-public methods, and signatures using
 * never-view-safe types are build errors.
 */
public final class FunctionRegistry {
  private final CompilerEnv env;

  public FunctionRegistry(CompilerEnv env) {
    this.env = env;
  }

  public Set<String> namespaces() {
    throw new UnsupportedOperationException("TODO(#4): FunctionRegistry.namespaces " + env.basePackage());
  }

  /** The unique applicable overload of {@code ns.name} for {@code argTypes}, if any. */
  public Optional<ExecutableElement> lookup(String namespace, String name, List<TypeMirror> argTypes) {
    throw new UnsupportedOperationException("TODO(#4): FunctionRegistry.lookup");
  }
}
