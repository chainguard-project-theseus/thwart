package dev.tessera.compiler.types;

import dev.tessera.compiler.Backports;
import java.util.List;

/**
 * Function namespace classes registered automatically when present on the compile classpath.
 * Adding a class here widens every application's attack surface: keep it short and reviewed.
 */
final class BuiltinNamespaces {
  private BuiltinNamespaces() {}

  static final List<String> CLASSES =
      Backports.listOf(
          "dev.tessera.runtime.fn.Strings",
          "dev.tessera.runtime.fn.Numbers",
          "dev.tessera.runtime.fn.Temporals",
          "dev.tessera.runtime.fn.Lists",
          "dev.tessera.runtime.fn.Css",
          "dev.tessera.runtime.fn.Html",
          "dev.tessera.spring.fn.Auth");

  /** The namespaces of the {@code dev.tessera.runtime.fn} classes above, for messages ({@code auth} needs the starter). */
  static final List<String> NAMES = Backports.listOf("strings", "numbers", "temporals", "lists", "css", "html");
}
