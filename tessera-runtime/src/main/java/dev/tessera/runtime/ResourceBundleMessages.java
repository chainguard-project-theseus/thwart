package dev.tessera.runtime;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * {@link Messages} over {@link java.util.ResourceBundle} (for use without Spring). Missing keys
 * render as {@code ??key_locale??}. Like Spring's {@code MessageSource}, a message goes through
 * {@link MessageFormat} only when there are arguments. Lookup falls back from the locale to the
 * base bundle, never to the JVM default locale.
 */
public final class ResourceBundleMessages implements Messages {
  private static final ResourceBundle.Control NO_DEFAULT_LOCALE =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private final String baseName;
  private final Locale locale;

  public ResourceBundleMessages(String baseName, Locale locale) {
    this.baseName = baseName;
    this.locale = locale;
  }

  @Override
  public String get(String key, Object... args) {
    String pattern;
    try {
      pattern = ResourceBundle.getBundle(baseName, locale, NO_DEFAULT_LOCALE).getString(key);
    } catch (MissingResourceException e) {
      return "??" + key + "_" + locale + "??";
    }
    if (args == null || args.length == 0) {
      return pattern;
    }
    return new MessageFormat(pattern, locale).format(args);
  }
}
