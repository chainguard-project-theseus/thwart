package dev.tessera.compiler.messages;

import java.nio.file.Path;
import java.util.Set;

/**
 * Message keys known at build time, from the default-locale bundle
 * ({@code -Atessera.messages=<base path>}, reading {@code <base>.properties}). When the option
 * is not set the bundle is unchecked and every key is accepted (with a warning per template).
 */
public final class MessageBundle {
  private final Set<String> keys;

  private MessageBundle(Set<String> keys) {
    this.keys = keys;
  }

  /** No bundle configured: keys are not checked. */
  public static MessageBundle unchecked() {
    return new MessageBundle(null);
  }

  /** Loads {@code basePath + ".properties"}; a missing file is reported by the caller. */
  public static MessageBundle load(Path basePath) {
    throw new UnsupportedOperationException("TODO(#16): MessageBundle.load " + basePath);
  }

  public boolean isChecked() {
    return keys != null;
  }

  public boolean contains(String key) {
    return keys == null || keys.contains(key);
  }
}
