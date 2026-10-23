package dev.tessera.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A single CSS value that is safe to place in a {@code style} attribute (#182): a colour or a
 * length. Only built by the validating factories {@link #color(String)} and {@link #length(String)},
 * which return {@code null} for anything else. The text of a {@code CssValue} contains only ASCII
 * letters, digits, {@code #}, {@code .}, {@code %} and {@code -}, so it can never end a
 * declaration ({@code ;}, <code>}</code>), call a function ({@code url(...)}, {@code expression(...)}),
 * open a string or comment, or carry a CSS escape ({@code \}).
 *
 * <p>Templates build one with the built-in {@code css} namespace ({@code css.color(c)}), and the
 * {@code style} attribute accepts it next to string literals and numbers.
 */
public final class CssValue {
  private static final Pattern HEX = Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
  private static final Pattern LENGTH =
      Pattern.compile("-?(?:[0-9]{1,10}(?:\\.[0-9]{1,10})?|\\.[0-9]{1,10})(?:px|em|rem|%|vh|vw)?");

  /** CSS Color Module Level 4 named colours, plus {@code transparent} and {@code currentcolor}. */
  private static final Set<String> NAMED =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "aliceblue", "antiquewhite", "aqua", "aquamarine", "azure", "beige", "bisque", "black",
                  "blanchedalmond", "blue", "blueviolet", "brown", "burlywood", "cadetblue", "chartreuse",
                  "chocolate", "coral", "cornflowerblue", "cornsilk", "crimson", "cyan", "darkblue",
                  "darkcyan", "darkgoldenrod", "darkgray", "darkgreen", "darkgrey", "darkkhaki",
                  "darkmagenta", "darkolivegreen", "darkorange", "darkorchid", "darkred", "darksalmon",
                  "darkseagreen", "darkslateblue", "darkslategray", "darkslategrey", "darkturquoise",
                  "darkviolet", "deeppink", "deepskyblue", "dimgray", "dimgrey", "dodgerblue",
                  "firebrick", "floralwhite", "forestgreen", "fuchsia", "gainsboro", "ghostwhite", "gold",
                  "goldenrod", "gray", "green", "greenyellow", "grey", "honeydew", "hotpink", "indianred",
                  "indigo", "ivory", "khaki", "lavender", "lavenderblush", "lawngreen", "lemonchiffon",
                  "lightblue", "lightcoral", "lightcyan", "lightgoldenrodyellow", "lightgray",
                  "lightgreen", "lightgrey", "lightpink", "lightsalmon", "lightseagreen", "lightskyblue",
                  "lightslategray", "lightslategrey", "lightsteelblue", "lightyellow", "lime",
                  "limegreen", "linen", "magenta", "maroon", "mediumaquamarine", "mediumblue",
                  "mediumorchid", "mediumpurple", "mediumseagreen", "mediumslateblue",
                  "mediumspringgreen", "mediumturquoise", "mediumvioletred", "midnightblue", "mintcream",
                  "mistyrose", "moccasin", "navajowhite", "navy", "oldlace", "olive", "olivedrab",
                  "orange", "orangered", "orchid", "palegoldenrod", "palegreen", "paleturquoise",
                  "palevioletred", "papayawhip", "peachpuff", "peru", "pink", "plum", "powderblue",
                  "purple", "rebeccapurple", "red", "rosybrown", "royalblue", "saddlebrown", "salmon",
                  "sandybrown", "seagreen", "seashell", "sienna", "silver", "skyblue", "slateblue",
                  "slategray", "slategrey", "snow", "springgreen", "steelblue", "tan", "teal", "thistle",
                  "tomato", "turquoise", "violet", "wheat", "white", "whitesmoke", "yellow",
                  "yellowgreen", "transparent", "currentcolor")));

  private final String value;

  private CssValue(String value) {
    this.value = value;
  }

  /**
   * A colour: {@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa} or a CSS named
   * colour (case-insensitive; names are lower-cased). Surrounding whitespace is ignored. Returns
   * {@code null} for {@code null} and for anything else, including {@code rgb(...)}.
   */
  public static CssValue color(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    if (HEX.matcher(t).matches()) {
      return new CssValue(t);
    }
    String lower = t.toLowerCase(Locale.ROOT);
    return NAMED.contains(lower) ? new CssValue(lower) : null;
  }

  /**
   * A number with an optional unit {@code px}, {@code em}, {@code rem}, {@code %}, {@code vh} or
   * {@code vw} ({@code 12px}, {@code -1.5em}, {@code .5}, {@code 50%}). Surrounding whitespace is
   * ignored. Returns {@code null} for {@code null} and for anything else.
   */
  public static CssValue length(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return LENGTH.matcher(t).matches() ? new CssValue(t) : null;
  }

  /** The CSS text. */
  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CssValue && value.equals(((CssValue) o).value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
