package dev.tessera.runtime;

/**
 * Resolves message keys for {@code #{key(args)}}. Implementations are bound to one locale. The
 * returned text is treated as plain text and escaped for its output context.
 */
@FunctionalInterface
public interface Messages {

  /** Formats message {@code key} with {@code args} ({@link java.text.MessageFormat} style). */
  String get(String key, Object... args);

  /** Returns {@code ??key??} for every key, like Thymeleaf does for missing messages. */
  Messages NONE = (key, args) -> "??" + key + "??";
}
