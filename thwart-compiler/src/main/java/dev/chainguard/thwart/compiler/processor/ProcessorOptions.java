package dev.chainguard.thwart.compiler.processor;

import dev.chainguard.thwart.compiler.CompilerEnv;
import dev.chainguard.thwart.compiler.JavaStrings;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * javac {@code -A} options read by {@link ThwartProcessor}.
 *
 * @param templates {@code thwart.templates} (required): absolute path of the templates root,
 *     e.g. {@code ${project.basedir}/src/main/resources/templates}
 * @param basePackage {@code thwart.package}: package for generated classes
 * @param messages {@code thwart.messages}: optional comma-separated message bundle basenames,
 *     each a path without {@code .properties}, e.g. {@code
 *     ${project.basedir}/src/main/resources/messages}; a locale suffix ({@code messages_en}) names
 *     the default locale (see {@code MessageBundle}). Empty when not set.
 */
public final class ProcessorOptions {
  private final Path templates;
  private final String basePackage;
  private final List<Path> messages;

  public ProcessorOptions(Path templates, String basePackage, List<Path> messages) {
    this.templates = templates;
    this.basePackage = basePackage;
    this.messages = messages;
  }

  public Path templates() {
    return templates;
  }

  public String basePackage() {
    return basePackage;
  }

  public List<Path> messages() {
    return messages;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProcessorOptions)) {
      return false;
    }
    ProcessorOptions that = (ProcessorOptions) o;
    return Objects.equals(templates, that.templates)
        && Objects.equals(basePackage, that.basePackage)
        && Objects.equals(messages, that.messages);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(templates);
    h = 31 * h + Objects.hashCode(basePackage);
    h = 31 * h + Objects.hashCode(messages);
    return h;
  }

  @Override
  public String toString() {
    return "ProcessorOptions[templates=" + templates + ", basePackage=" + basePackage
        + ", messages=" + messages + "]";
  }

  public static final String TEMPLATES = "thwart.templates";
  public static final String PACKAGE = "thwart.package";
  public static final String MESSAGES = "thwart.messages";

  /** Parses options; {@code templates} is null and {@code messages} empty when missing. */
  public static ProcessorOptions parse(Map<String, String> options) {
    String t = options.get(TEMPLATES);
    String p = options.getOrDefault(PACKAGE, CompilerEnv.DEFAULT_PACKAGE);
    String m = options.get(MESSAGES);
    List<Path> messages = new ArrayList<>();
    if (m != null) {
      for (String base : m.split(",")) {
        if (!JavaStrings.isJavaBlank(base)) {
          messages.add(Paths.get(JavaStrings.stripJava(base)));
        }
      }
    }
    return new ProcessorOptions(t == null || JavaStrings.isJavaBlank(t) ? null : Paths.get(t), p, messages);
  }
}
