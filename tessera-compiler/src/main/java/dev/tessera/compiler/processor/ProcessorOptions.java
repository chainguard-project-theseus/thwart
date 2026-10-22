package dev.tessera.compiler.processor;

import dev.tessera.compiler.CompilerEnv;
import java.nio.file.Path;
import java.util.Map;

/**
 * javac {@code -A} options read by {@link TesseraProcessor}.
 *
 * @param templates {@code tessera.templates} (required): absolute path of the templates root,
 *     e.g. {@code ${project.basedir}/src/main/resources/templates}
 * @param basePackage {@code tessera.package}: package for generated classes
 * @param messages {@code tessera.messages}: optional base path of the default message bundle
 *     without {@code .properties}, e.g. {@code ${project.basedir}/src/main/resources/messages}
 */
public record ProcessorOptions(Path templates, String basePackage, Path messages) {

  public static final String TEMPLATES = "tessera.templates";
  public static final String PACKAGE = "tessera.package";
  public static final String MESSAGES = "tessera.messages";

  /** Parses options; {@code templates} is null when the option is missing. */
  public static ProcessorOptions parse(Map<String, String> options) {
    String t = options.get(TEMPLATES);
    String p = options.getOrDefault(PACKAGE, CompilerEnv.DEFAULT_PACKAGE);
    String m = options.get(MESSAGES);
    return new ProcessorOptions(
        t == null || t.isBlank() ? null : Path.of(t),
        p,
        m == null || m.isBlank() ? null : Path.of(m));
  }
}
