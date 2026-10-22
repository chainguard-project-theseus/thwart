package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunctions;

/**
 * Built-in {@code strings} namespace (Thymeleaf's {@code #strings}). Always registered.
 *
 * <p>TODO(#6): implement the functions listed in docs/issues.md.
 */
@TemplateFunctions("strings")
public final class Strings {
  private Strings() {}
}
