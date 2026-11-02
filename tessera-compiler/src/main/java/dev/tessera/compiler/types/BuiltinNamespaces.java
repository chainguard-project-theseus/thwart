package dev.tessera.compiler.types;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.html.HtmlParser;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Function namespace classes registered automatically when present on the compile classpath.
 * Adding a class here widens every application's attack surface: keep it short and reviewed.
 */
final class BuiltinNamespaces {
  private BuiltinNamespaces() {}

  private static final String RUNTIME_FN = "dev.tessera.runtime.fn.";

  static final List<String> CLASSES =
      Backports.listOf(
          RUNTIME_FN + "Strings",
          RUNTIME_FN + "Numbers",
          RUNTIME_FN + "Temporals",
          RUNTIME_FN + "Lists",
          RUNTIME_FN + "Css",
          RUNTIME_FN + "Html",
          "dev.tessera.spring.fn.Auth");

  /**
   * The namespaces of the {@code dev.tessera.runtime.fn} classes above, for messages ({@code auth}
   * needs the starter): each class's {@code @TemplateFunctions} value is its lower-cased simple name.
   */
  static final List<String> NAMES =
      Backports.listCopy(
          CLASSES.stream()
              .filter(c -> c.startsWith(RUNTIME_FN))
              .map(c -> HtmlParser.asciiLower(c.substring(RUNTIME_FN.length())))
              .collect(Collectors.toList()));
}
