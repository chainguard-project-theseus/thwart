package dev.tessera.compiler;

import dev.tessera.compiler.messages.MessageBundle;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
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
 * @param functionIndex maps any other namespace to the qualified name of the class that declared it
 *     in an earlier compilation, or null (the processor reads the index from the classpath, #118)
 */
public final class CompilerEnv {
  private final Elements elements;
  private final Types types;
  private final DiagnosticSink diagnostics;
  private final String basePackage;
  private final MessageBundle messages;
  private final List<TypeElement> functionNamespaces;
  private final Function<String, String> functionIndex;

  /** An environment without a function index (only {@code functionNamespaces} and built-ins). */
  public CompilerEnv(
      Elements elements,
      Types types,
      DiagnosticSink diagnostics,
      String basePackage,
      MessageBundle messages,
      List<TypeElement> functionNamespaces) {
    this(elements, types, diagnostics, basePackage, messages, functionNamespaces, ns -> null);
  }

  public CompilerEnv(
      Elements elements,
      Types types,
      DiagnosticSink diagnostics,
      String basePackage,
      MessageBundle messages,
      List<TypeElement> functionNamespaces,
      Function<String, String> functionIndex) {
    this.functionIndex = Objects.requireNonNull(functionIndex);
    this.elements = elements;
    this.types = types;
    this.diagnostics = diagnostics;
    this.basePackage = basePackage;
    this.messages = messages;
    this.functionNamespaces = Backports.listCopy(functionNamespaces);
  }

  public Elements elements() {
    return elements;
  }

  public Types types() {
    return types;
  }

  public DiagnosticSink diagnostics() {
    return diagnostics;
  }

  public String basePackage() {
    return basePackage;
  }

  public MessageBundle messages() {
    return messages;
  }

  public List<TypeElement> functionNamespaces() {
    return functionNamespaces;
  }

  public Function<String, String> functionIndex() {
    return functionIndex;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CompilerEnv)) {
      return false;
    }
    CompilerEnv that = (CompilerEnv) o;
    return Objects.equals(elements, that.elements)
        && Objects.equals(types, that.types)
        && Objects.equals(diagnostics, that.diagnostics)
        && Objects.equals(basePackage, that.basePackage)
        && Objects.equals(messages, that.messages)
        && Objects.equals(functionNamespaces, that.functionNamespaces)
        && Objects.equals(functionIndex, that.functionIndex);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(elements);
    h = 31 * h + Objects.hashCode(types);
    h = 31 * h + Objects.hashCode(diagnostics);
    h = 31 * h + Objects.hashCode(basePackage);
    h = 31 * h + Objects.hashCode(messages);
    h = 31 * h + Objects.hashCode(functionNamespaces);
    h = 31 * h + Objects.hashCode(functionIndex);
    return h;
  }

  @Override
  public String toString() {
    return "CompilerEnv[elements=" + elements + ", types=" + types + ", diagnostics=" + diagnostics
        + ", basePackage=" + basePackage + ", messages=" + messages
        + ", functionNamespaces=" + functionNamespaces + "]";
  }

  public static final String DEFAULT_PACKAGE = "tessera.generated";
}
