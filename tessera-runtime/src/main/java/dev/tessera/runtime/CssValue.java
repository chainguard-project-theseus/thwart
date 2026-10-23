package dev.tessera.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A single CSS value that is safe to place in a {@code style} attribute (#182): a colour, a
 * length or an image URL. Only built by the validating factories {@link #color(String)},
 * {@link #length(String)} and {@link #url(String)}, which return {@code null} for anything else.
 * The text of a colour or length contains only ASCII letters, digits, {@code #}, {@code .},
 * {@code %} and {@code -}, so it can never end a declaration ({@code ;}, <code>}</code>), call a
 * function ({@code url(...)}, {@code expression(...)}), open a string or comment, or carry a CSS
 * escape ({@code \}). A URL is {@code url("...")} whose quoted part contains only URL characters
 * that have no meaning inside a CSS string (see {@link #url(String)}).
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
