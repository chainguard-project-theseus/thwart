package dev.tessera.compiler;

import dev.tessera.compiler.messages.MessageBundle;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Everything the compiler stages may use. Built by the annotation processor (or by tests).
 *
 * @param elements javac element utilities (compile classpath + sources being compiled)
 * @param types javac type utilities
 * @param diagnostics where all stages report problems
 * @param basePackage package for generated classes ({@code -Atessera.package}, default
 *     {@code tessera.generated})
 * @param messages message keys for build-time {@code #{...}} checks
 * @param functionNamespaces {@code @TemplateFunctions} classes found in the current
 *     compilation (built-in namespaces are added by the function registry)
 */
public record CompilerEnv(
    Elements elements,
    Types types,
    DiagnosticSink diagnostics,
    String basePackage,
    MessageBundle messages,
    List<TypeElement> functionNamespaces) {

  public static final String DEFAULT_PACKAGE = "tessera.generated";

  public CompilerEnv {
    functionNamespaces = List.copyOf(functionNamespaces);
  }
}
