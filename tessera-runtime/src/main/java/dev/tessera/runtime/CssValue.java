package dev.tessera.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single CSS value that is safe to place in a {@code style} attribute (#182): a colour, a
 * length, an image URL or a custom property reference. Only built by the validating factories
 * {@link #color(String)}, {@link #length(String)}, {@link #url(String)} and {@link #var(String)},
 * which return {@code null} for anything else. Each accepts a strict positive grammar and rebuilds
 * the value from what it parsed. The text of a colour, length or {@code var(...)} contains only
 * ASCII letters, digits, {@code #}, {@code .}, {@code %}, {@code -}, and in a colour function or
 * {@code var} the parentheses, {@code ,}, space and {@code /} followed by a space. The only
 * functions are {@code rgb}, {@code rgba}, {@code hsl}, {@code hsla} and {@code var} with
 * numbers or one name as arguments. So the value can never end a declaration ({@code ;},
 * <code>}</code>), call another function ({@code url(...)}, {@code expression(...)}), open a
 * string or comment, or carry a CSS escape ({@code \}). A URL is {@code url("...")} whose quoted
 * part contains only URL characters that have no meaning inside a CSS string (see
 * {@link #url(String)}).
 *
 * <p>Templates build one with the built-in {@code css} namespace ({@code css.color(c)}), and the
 * {@code style} attribute accepts it next to string literals and numbers.
 */
public final class CssValue {
  private static final Pattern HEX = Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
  private static final Pattern LENGTH =
      Pattern.compile("-?(?:[0-9]{1,10}(?:\\.[0-9]{1,10})?|\\.[0-9]{1,10})(?:px|em|rem|%|vh|vw)?");

  /** A colour function argument: a number with an optional unit ({@code 255}, {@code 50%}, {@code 120deg}, {@code .5}). */
  private static final String ARG = "-?(?:[0-9]{1,10}(?:\\.[0-9]{1,10})?|\\.[0-9]{1,10})(?:%|deg|rad|grad|turn)?";

  /** {@code rgb(255, 0, 0)}, {@code hsla(120deg, 50%, 50%, .5)}: comma-separated arguments (#429). */
  private static final Pattern COLOR_COMMAS =
      Pattern.compile(
          "(rgba?|hsla?)\\( *(" + ARG + ") *, *(" + ARG + ") *, *(" + ARG + ") *(?:, *(" + ARG + ") *)?\\)");

  /** {@code rgb(255 0 0)}, {@code hsl(120deg 50% 50% / .5)}: space-separated, alpha after {@code /} (#429). */
  private static final Pattern COLOR_SPACES =
      Pattern.compile(
          "(rgba?|hsla?)\\( *(" + ARG + ") +(" + ARG + ") +(" + ARG + ") *(?:/ *(" + ARG + ") *)?\\)");

  /** A custom property name (#429): {@code --} and an identifier. */
  private static final Pattern CUSTOM_PROPERTY = Pattern.compile("--[a-zA-Z-][a-zA-Z0-9-]{0,255}");

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
   * A colour: {@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa}, a CSS named
   * colour (case-insensitive; names are lower-cased), or (#429) {@code rgb}, {@code rgba}, {@code hsl} or
   * {@code hsla} with three or four numeric arguments, each a number with an optional {@code %},
   * {@code deg}, {@code rad}, {@code grad} or {@code turn}, separated by commas
   * ({@code rgb(0, 128, 255)}, {@code hsla(120deg, 50%, 50%, .5)}) or by spaces with the alpha after
   * {@code /} ({@code rgb(0 128 255 / 50%)}). A colour function is rebuilt from its parsed numbers,
   * lower-cased, with single spaces. Nothing else is accepted inside the parentheses: no nested
   * function ({@code calc}, {@code var}), keyword ({@code none}, {@code from}) or other text.
   * Surrounding whitespace is ignored. Returns {@code null} for {@code null} and for anything else.
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
    if (NAMED.contains(lower)) {
      return new CssValue(lower);
    }
    Matcher m = COLOR_COMMAS.matcher(lower);
    if (m.matches()) {
      return colorFunction(m, ", ", ", ");
    }
    m = COLOR_SPACES.matcher(lower);
    return m.matches() ? colorFunction(m, " ", " / ") : null;
  }

  /** {@code fn(a<sep>b<sep>c)} or {@code fn(a<sep>b<sep>c<alphaSep>d)} from a matched colour function. */
  private static CssValue colorFunction(Matcher m, String sep, String alphaSep) {
    StringBuilder b = new StringBuilder(m.group(1)).append('(').append(m.group(2)).append(sep).append(m.group(3))
        .append(sep).append(m.group(4));
    if (m.group(5) != null) {
      b.append(alphaSep).append(m.group(5));
    }
    return new CssValue(b.append(')').toString());
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

  /**
   * A reference to a custom property (#429): {@code var(--name)} for {@code --name}, an identifier
   * after {@code --} (ASCII letters, digits and {@code -}, not starting with a digit, at most 258
   * chars in all). Surrounding whitespace is ignored. Returns {@code null} for {@code null} and for
   * anything else, including a name without {@code --} and a fallback ({@code --a, red}).
   */
  public static CssValue var(String name) {
    if (name == null) {
      return null;
    }
    String t = name.trim();
    return CUSTOM_PROPERTY.matcher(t).matches() ? new CssValue("var(" + t + ")") : null;
  }

  /** Longest URL {@link #url(String)} accepts, in chars. */
  static final int MAX_URL_LENGTH = 4096;

  /**
   * An image URL for {@code background}, {@code background-image} and the like (#238), written as
   * {@code url("...")}. Surrounding whitespace is ignored. The URL must be relative
   * ({@code /img/a.png}, {@code a.png}, {@code ?v=1}), protocol-relative ({@code //cdn/a.png}) or
   * {@code http}/{@code https}: the URL context's scheme check without {@code mailto} and
   * {@code tel}. {@code data:}, {@code javascript:} and every other scheme give {@code null}, as do
   * {@code null}, the empty string, control characters (tab, CR and LF included), unpaired
   * surrogates and URLs longer than {@value #MAX_URL_LENGTH} chars.
   *
   * <p>The quoted part keeps only ASCII letters, digits and {@code - . _ ~ : / ? # [ ] @ ! $ & + , ; =}
   * plus {@code %} when it starts a {@code %XX} escape. Everything else is percent-encoded as UTF-8:
   * {@code "} {@code '} {@code \} {@code (} {@code )} {@code *} {@code <} {@code >}, space, a
   * stray {@code %} and non-ASCII. So the value can never close the string or the {@code url(...)},
   * carry a CSS escape or start a comment.
   */
  public static CssValue url(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    if (t.isEmpty() || t.length() > MAX_URL_LENGTH) {
      return null;
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c < ' ' || c == 0x7f) {
        return null;
      }
    }
    if (!Escapers.isAllowedUrl(t) || hasScheme(t, "mailto") || hasScheme(t, "tel")) {
      return null;
    }
    StringBuilder b = new StringBuilder(t.length() + 16).append("url(\"");
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (isUrlSafe(c)) {
        b.append(c);
      } else if (c == '%' && i + 2 < t.length() && isHex(t.charAt(i + 1)) && isHex(t.charAt(i + 2))) {
        b.append(c);
      } else if (c < 0x80) {
        percent(b, c);
      } else {
        int cp = t.codePointAt(i);
        if (Character.isSurrogate((char) cp)) {
          return null; // unpaired surrogate
        }
        i += Character.charCount(cp) - 1;
        byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
        for (byte x : utf8) {
          percent(b, x & 0xff);
        }
      }
    }
    return new CssValue(b.append("\")").toString());
  }

  /** Whether {@code t} (no control characters) starts with {@code scheme:}, ASCII case-insensitively. */
  private static boolean hasScheme(String t, String scheme) {
    return t.length() > scheme.length()
        && t.charAt(scheme.length()) == ':'
        && t.regionMatches(true, 0, scheme, 0, scheme.length());
  }

  private static boolean isUrlSafe(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || "-._~:/?#[]@!$&+,;=".indexOf(c) >= 0;
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static void percent(StringBuilder b, int octet) {
    b.append('%').append(HEX_DIGITS[octet >> 4]).append(HEX_DIGITS[octet & 0xf]);
  }

  private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

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
