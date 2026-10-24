package dev.tessera.compiler.processor;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * javac {@code -A} options read by {@link TesseraProcessor}.
 *
 * @param templates {@code tessera.templates} (required): absolute path of the templates root,
 *     e.g. {@code ${project.basedir}/src/main/resources/templates}
 * @param basePackage {@code tessera.package}: package for generated classes
 * @param messages {@code tessera.messages}: optional comma-separated message bundle basenames,
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

  public static final String TEMPLATES = "tessera.templates";
  public static final String PACKAGE = "tessera.package";
  public static final String MESSAGES = "tessera.messages";

  /** Parses options; {@code templates} is null and {@code messages} empty when missing. */
  public static ProcessorOptions parse(Map<String, String> options) {
    String t = options.get(TEMPLATES);
    String p = options.getOrDefault(PACKAGE, CompilerEnv.DEFAULT_PACKAGE);
    String m = options.get(MESSAGES);
    List<Path> messages = new ArrayList<>();
    if (m != null) {
      for (String base : m.split(",")) {
        if (!Backports.isBlank(base)) {
          messages.add(Paths.get(Backports.strip(base)));
        }
      }
    }
    return new ProcessorOptions(t == null || Backports.isBlank(t) ? null : Paths.get(t), p, messages);
  }
}
