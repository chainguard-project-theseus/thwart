package dev.tessera.runtime.fn;

import dev.tessera.runtime.CssValue;
import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;

/**
 * Built-in {@code css} namespace: validated values for {@code style} attributes. Always
 * registered. Invalid or {@code null} input gives {@code null}: a {@code t:style} that is only
 * that value is omitted, and inside {@code |...|} or {@code +} it prints {@code null}, which the
 * browser drops as an invalid declaration.
 */
@TemplateFunctions("css")
public final class Css {
  private Css() {}

  /** {@link CssValue#color(String)}: {@code #rgb}, {@code #rrggbb}, a named colour, {@code rgb(0, 128, 255)}, {@code hsl(...)}, ... */
  @TemplateFunction
  public static CssValue color(String s) {
    return CssValue.color(s);
  }

  /** {@link CssValue#length(String)}: {@code 12px}, {@code 1.5em}, {@code 50%}, ... */
  @TemplateFunction
  public static CssValue length(String s) {
    return CssValue.length(s);
  }

  /** {@link CssValue#url(String)}: {@code url("/img/a.png")} for a relative or http(s) URL. */
  @TemplateFunction
  public static CssValue url(String s) {
    return CssValue.url(s);
  }

  /** {@link CssValue#var(String)}: {@code var(--brand)} for the custom property name {@code --brand}. */
  @TemplateFunction
  public static CssValue var(String name) {
    return CssValue.var(name);
  }
}
