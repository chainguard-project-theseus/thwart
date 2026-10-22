package dev.tessera.compiler;

/**
 * Compiler output written through the annotation processor's {@code Filer}.
 *
 * @param kind Java source or class-output resource
 * @param name fully qualified class name for {@code JAVA}; resource path for {@code RESOURCE}
 *     (e.g. {@code META-INF/services/dev.tessera.runtime.TemplateRegistry})
 * @param content file contents
 */
public record GeneratedFile(Kind kind, String name, String content) {
  public enum Kind {
    JAVA,
    RESOURCE
  }
}
