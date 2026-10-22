package dev.tessera.runtime;

import java.util.Locale;

/**
 * {@link Messages} over {@link java.util.ResourceBundle} (for use without Spring). Missing keys
 * render as {@code ??key_locale??}.
 */
public final class ResourceBundleMessages implements Messages {
  private final String baseName;
  private final Locale locale;

  public ResourceBundleMessages(String baseName, Locale locale) {
    this.baseName = baseName;
    this.locale = locale;
  }

  @Override
  public String get(String key, Object... args) {
    throw new UnsupportedOperationException("TODO(#6): ResourceBundleMessages.get (" + baseName + ", " + locale + ")");
  }
}
