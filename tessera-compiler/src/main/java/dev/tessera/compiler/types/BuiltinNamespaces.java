package dev.tessera.compiler.types;

import java.util.List;

/**
 * Function namespace classes registered automatically when present on the compile classpath.
 * Adding a class here widens every application's attack surface: keep it short and reviewed.
 */
final class BuiltinNamespaces {
  private BuiltinNamespaces() {}

  static final List<String> CLASSES =
      List.of(
          "dev.tessera.runtime.fn.Strings",
          "dev.tessera.runtime.fn.Numbers",
          "dev.tessera.runtime.fn.Temporals",
          "dev.tessera.runtime.fn.Lists",
          "dev.tessera.spring.fn.Auth");
}
