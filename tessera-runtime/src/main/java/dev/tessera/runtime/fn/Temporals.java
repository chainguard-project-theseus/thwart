package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunctions;

/**
 * Built-in {@code temporals} namespace (Thymeleaf's {@code #temporals}). Always registered.
 *
 * <p>TODO(#6): implement the functions listed in docs/issues.md.
 */
@TemplateFunctions("temporals")
public final class Temporals {
  private Temporals() {}
}
