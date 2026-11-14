package dev.chainguard.thwart.runtime;

import dev.chainguard.thwart.runtime.internal.Escapers;

/**
 * Resolves message keys for {@code #{key(args)}}. Implementations are bound to one locale. The
 * returned text is escaped for its output context, or, in {@code t:utext} and {@code [(...)]},
 * sanitized by the fixed message policy ({@link Escapers#htmlMessage}).
 */
@FunctionalInterface
public interface Messages {

  /** Formats message {@code key} with {@code args} ({@link java.text.MessageFormat} style). */
  String get(String key, Object... args);

  /** The arguments of a message without any, shared: generated code allocates no array for it. */
  Object[] NO_ARGS = {};

  /** Returns {@code ??key??} for every key, like Thymeleaf does for missing messages. */
  Messages NONE = (key, args) -> "??" + key + "??";
}
