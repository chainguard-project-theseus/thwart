package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunctions;

/**
 * Built-in {@code numbers} namespace (Thymeleaf's {@code #numbers}). Always registered.
 *
 * <p>TODO(#6): implement the functions listed in docs/issues.md.
 */
@TemplateFunctions("numbers")
public final class Numbers {
  private Numbers() {}
}
