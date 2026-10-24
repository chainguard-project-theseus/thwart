package dev.tessera.compiler.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent checks that an escaped value cannot leave its output context. These do not reuse
 * the runtime escapers: they parse the output the way a browser would and compare with the input.
 * Each method returns null when the output is safe, or a reason when it is not.
 */
final class ContextOracle {
  private ContextOracle() {}

  static final String INVALID_URL = "about:invalid#tessera";

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto", "tel");
  private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|#39);");
  private static final Pattern BROWSER_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

  /** What the HTML escapers promise to preserve: NUL and unpaired surrogates become U+FFFD. */
  static String wellFormed(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
        sb.append(c).append(s.charAt(++i));
      } else if (c == '\0' || Character.isSurrogate(c)) {
        sb.append('�');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Element text or a double-quoted attribute value: no markup or quote characters, every
   * {@code &} starts one of the escaper's entities, no NUL or lone surrogate, and decoding gives
   * back {@code expected} (if not null).
   */
  static String html(String out, String expected) {
    for (int i = 0; i < out.length(); i++) {
      char c = out.charAt(i);
      if (c == '<' || c == '>' || c == '"' || c == '\'' || c == '\0') {
        return "raw " + describe(c) + " at " + i;
      }
      if (c == '&' && !ENTITY.matcher(out).region(i, out.length()).lookingAt()) {
        return "bare & at " + i;
      }
      if (Character.isHighSurrogate(c) && i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return "unpaired surrogate at " + i;
      }
    }
    if (expected != null && !decodeHtml(out).equals(wellFormed(expected))) {
      return "decodes to a different value";
    }
    return null;
  }

  /** What the element-text escaper writes between braces (#248). */
  static final String BRACE_BREAK = "<!---->";

  /**
   * Element text: like {@link #html}, except that empty comments may separate braces, and no
   * client-side {@code {{} interpolation can form, whatever text is written next to the value: no
   * text between the comments contains {@code {{}, the value neither starts nor ends with a bare
   * {@code {}, and every comment touches a brace. Without the comments, it decodes to
   * {@code expected} (if not null).
   */
  static String text(String out, String expected) {
    return text(out, expected, true);
  }

  /**
   * As {@link #text(String, String)}; without {@code edges}, the slot is the middle of a value
   * (a literal substitution), so its ends need no comment.
   */
  static String text(String out, String expected, boolean edges) {
    String[] parts = out.split(Pattern.quote(BRACE_BREAK), -1);
    for (int i = 0; i < parts.length; i++) {
      String r = html(parts[i], null);
      if (r != null) {
        return r;
      }
      if (decodeHtml(parts[i]).contains("{{")) {
        return "unbroken {{ in " + parts[i];
      }
      if (i > 0 && !parts[i - 1].endsWith("{") && !parts[i].startsWith("{")) {
        return "comment not next to a brace";
      }
    }
    if (edges && (out.startsWith("{") || out.endsWith("{"))) {
      return "bare { at the edge";
    }
    if (expected != null && !decodeHtml(String.join("", parts)).equals(wellFormed(expected))) {
      return "decodes to a different value";
    }
    return null;
  }

  /**
   * A URL attribute value: safe as an attribute, and the scheme a browser would see after
   * decoding is allowed. Unless the value was replaced, it must decode to {@code expected}.
   */
  static String url(String out, String expected) {
    String r = html(out, null);
    if (r != null) {
      return r;
    }
    String value = decodeHtml(out);
    if (value.equals(INVALID_URL)) {
      return null;
    }
    String scheme = browserScheme(value);
    if (scheme != null && !ALLOWED_SCHEMES.contains(scheme)) {
      return "scheme " + scheme + " passed";
    }
    if (expected != null && !value.equals(wellFormed(expected))) {
      return "decodes to a different value";
    }
    return null;
  }

  /**
   * The {@code values} list of an SVG {@code <animate>}/{@code <set>} (#284): safe as an
   * attribute, and the scheme a browser would see in every {@code ;}-separated item is allowed
   * (SVG applies the items in turn), unless the whole list was replaced. Unless replaced, it must
   * decode to {@code expected}.
   */
  static String urlList(String out, String expected) {
    String r = html(out, null);
    if (r != null) {
      return r;
    }
    String value = decodeHtml(out);
    if (value.equals(INVALID_URL)) {
      return null;
    }
    for (String item : value.split(";", -1)) {
      String scheme = browserScheme(item);
      if (scheme != null && !ALLOWED_SCHEMES.contains(scheme)) {
        return "scheme " + scheme + " passed in list item " + item;
      }
    }
    if (expected != null && !value.equals(wellFormed(expected))) {
      return "decodes to a different value";
    }
    return null;
  }

  /** The URL scheme per the WHATWG URL parser, lower-cased, or null for a relative URL. */
  static String browserScheme(String url) {
    int start = 0;
    int end = url.length();
    while (start < end && url.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && url.charAt(end - 1) <= ' ') {
      end--;
    }
    String s = url.substring(start, end).replaceAll("[\t\n\r]", "");
    Matcher m = BROWSER_SCHEME.matcher(s);
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  /**
   * A JSON string literal inlined into a script, possibly inside a JS comment, regex or template
   * literal: exactly one double-quoted string, none of the characters that end a script element,
   * comment, regex or template literal or start an interpolation, and it decodes to
   * {@code expected} (if not null).
   */
  static String jsonString(String out, String expected) {
    if (out.length() < 2 || out.charAt(0) != '"' || out.charAt(out.length() - 1) != '"') {
      return "not a quoted string";
    }
    StringBuilder value = new StringBuilder();
    for (int i = 1; i < out.length() - 1; i++) {
      char c = out.charAt(i);
      switch (c) {
        case '"' -> {
          return "unescaped quote at " + i;
        }
        case '<', '>', '&', '\'', '`', '$', '/', ' ', ' ' -> {
          return "raw " + describe(c) + " at " + i;
        }
        case '\\' -> {
          if (i + 1 >= out.length() - 1) {
            return "dangling backslash";
          }
          char e = out.charAt(++i);
          switch (e) {
            case '"', '\\', '/' -> value.append(e);
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'u' -> {
              if (i + 4 > out.length() - 2 || !out.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                return "bad \\u escape at " + i;
              }
              value.append((char) Integer.parseInt(out.substring(i + 1, i + 5), 16));
              i += 4;
            }
            default -> {
              return "bad escape \\" + e;
            }
          }
        }
        default -> {
          if (c < 0x20 || c == 0x7f) {
            return "raw control " + describe(c);
          }
          if (Character.isHighSurrogate(c) && i + 1 < out.length() - 1 && Character.isLowSurrogate(out.charAt(i + 1))) {
            value.append(c).append(out.charAt(++i));
          } else if (Character.isSurrogate(c)) {
            return "raw unpaired surrogate at " + i;
          } else {
            value.append(c);
          }
        }
      }
    }
    if (expected != null && !value.toString().equals(expected)) {
      return "decodes to a different value";
    }
    return null;
  }

  /** Decodes exactly the entities the escapers produce (what a browser does to them). */
  static String decodeHtml(String s) {
    return s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&");
  }

  private static String describe(char c) {
    return c >= 0x20 && c < 0x7f ? "'" + c + "'" : String.format("U+%04X", (int) c);
  }
}
