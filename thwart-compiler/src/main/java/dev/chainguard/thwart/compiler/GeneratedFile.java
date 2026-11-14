package dev.chainguard.thwart.compiler;

import java.util.Objects;

/**
 * Compiler output written through the annotation processor's {@code Filer}.
 *
 * @param kind Java source or class-output resource
 * @param name fully qualified class name for {@code JAVA}; resource path for {@code RESOURCE}
 *     (e.g. {@code META-INF/services/dev.chainguard.thwart.runtime.TemplateRegistry})
 * @param content file contents
 */
public final class GeneratedFile {
  private final Kind kind;
  private final String name;
  private final String content;

  public GeneratedFile(Kind kind, String name, String content) {
    this.kind = kind;
    this.name = name;
    this.content = content;
  }

  public Kind kind() {
    return kind;
  }

  public String name() {
    return name;
  }

  public String content() {
    return content;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof GeneratedFile)) {
      return false;
    }
    GeneratedFile that = (GeneratedFile) o;
    return Objects.equals(kind, that.kind)
        && Objects.equals(name, that.name)
        && Objects.equals(content, that.content);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(kind);
    h = 31 * h + Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(content);
    return h;
  }

  @Override
  public String toString() {
    return "GeneratedFile[kind=" + kind + ", name=" + name + ", content=" + content + "]";
  }

  public enum Kind {
    JAVA,
    RESOURCE
  }
}
