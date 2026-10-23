package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fixed allow-list policy behind {@link SafeHtml#sanitize}. The input is tokenized and the
 * output rebuilt from scratch: it contains only allow-listed tags written here, text re-escaped by
 * {@link Escapers}, and on {@code <a>} an {@code href} that passed the URL scheme check plus a
 * constant {@code rel}. No input is copied through, so the result is safe however a browser would
 * have parsed the original: there are no comments, no raw-text or foreign-content elements, and no
 * other attributes.
 *
 * <p>Elements are balanced with an open-element stack: an end tag closes up to its matching open
 * element, unmatched end tags are ignored, and everything still open is closed at the end, so the
 * output cannot close the page's own elements.
 */
final class HtmlSanitizer {
  /** Kept elements beyond this nesting depth are dropped (their text is kept). */
  private static final int MAX_DEPTH = 100;

  private static final String REL = " rel=\"nofollow noopener\"";

  /** Allowed elements, written without attributes (except {@code a[href]}). */
  private static final Set<String> ALLOWED =
      set(
          "a", "b", "i", "em", "strong", "u", "s", "sub", "sup", "small", "mark", "code", "pre",
          "blockquote", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "span", "div",
          "br", "hr");

  private static final Set<String> VOID = set("br", "hr");

  /** Raw text and RCDATA elements: skipped, content included, up to their end tag. */
  private static final Set<String> RAW_TEXT =
      set("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript");

  /** Dropped with everything inside, found by counting nested tags of the same name. */
  private static final Set<String> DROP_CONTENT = set("svg", "math", "object", "template", "select");

  private static final Map<String, String> ENTITIES = new HashMap<>();

  static {
    String[] pairs = {
      "amp", "&", "AMP", "&", "lt", "<", "LT", "<", "gt", ">", "GT", ">", "quot", "\"",
      "QUOT", "\"", "apos", "'", "nbsp", " ", "Tab", "\t", "NewLine", "\n", "colon", ":",
      "sol", "/", "lpar", "(", "rpar", ")", "period", ".", "comma", ",", "excl", "!",
      "quest", "?", "num", "#", "semi", ";", "equals", "=", "copy", "©", "reg", "®",
      "trade", "™", "hellip", "…", "mdash", "—", "ndash", "–",
      "lsquo", "‘", "rsquo", "’", "ldquo", "“", "rdquo", "”",
      "sbquo", "‚", "bdquo", "„", "laquo", "«", "raquo", "»",
      "middot", "·", "bull", "•", "euro", "€", "pound", "£", "yen", "¥",
      "cent", "¢", "deg", "°", "plusmn", "±", "times", "×",
      "divide", "÷", "para", "¶", "sect", "§", "shy", "­",
    };
    for (int i = 0; i < pairs.length; i += 2) {
      ENTITIES.put(pairs[i], pairs[i + 1]);
    }
  }

  private final String s;
  private final int n;
  private int pos;
  private final StringBuilder out;
  private final List<String> open = new ArrayList<>();
  /** Element whose content is being dropped, or {@code null}. */
  private String dropping;

  private int dropDepth;

  /** Whether the tag just parsed by {@link #attributes} ended in {@code />}. */
  private boolean selfClosing;

  private HtmlSanitizer(String s) {
    this.s = s;
    this.n = s.length();
    this.out = new StringBuilder(n + 16);
  }

  static String sanitize(String html) {
    HtmlSanitizer p = new HtmlSanitizer(html);
    p.run();
    return p.out.toString();
  }

  private void run() {
    while (pos < n) {
      int lt = s.indexOf('<', pos);
      if (lt < 0) {
        text(pos, n);
        break;
      }
      text(pos, lt);
      pos = lt;
      if (!markup()) {
        text(pos, pos + 1); // a '<' that starts no markup is text
        pos++;
      }
    }
    for (int i = open.size() - 1; i >= 0; i--) {
      out.append("</").append(open.get(i)).append('>');
    }
  }

  /** Consumes the markup at {@code pos} (a '<'); returns false if it is a literal '<'. */
  private boolean markup() {
    if (s.startsWith("<!--", pos)) {
      skipComment();
      return true;
    }
    char next = pos + 1 < n ? s.charAt(pos + 1) : 0;
    if (next == '!' || next == '?') { // doctype, CDATA, processing instruction: bogus comment
      skipPast('>', pos + 2);
      return true;
    }
    if (next == '/') {
      char c = pos + 2 < n ? s.charAt(pos + 2) : 0;
      if (isAsciiLetter(c)) {
        pos += 2;
        String name = tagName();
        if (attributes() != null) {
          endTag(name);
        }
      } else if (c == '>') {
        pos += 3;
      } else if (pos + 2 >= n) {
        return false;
      } else {
        skipPast('>', pos + 2);
      }
      return true;
    }
    if (!isAsciiLetter(next)) {
      return false;
    }
    pos++;
    String name = tagName();
    Map<String, String> attrs = attributes();
    if (attrs != null) {
      startTag(name, attrs, selfClosing);
    }
    return true;
  }

  private void startTag(String name, Map<String, String> attrs, boolean selfClosing) {
    if (dropping != null) {
      if (name.equals(dropping) && !selfClosing) {
        dropDepth++;
      } else if (RAW_TEXT.contains(name)) {
        skipRawText(name);
      } else if (name.equals("plaintext")) {
        pos = n;
      }
      return;
    }
    if (RAW_TEXT.contains(name)) {
      skipRawText(name);
    } else if (name.equals("plaintext")) {
      pos = n; // everything after <plaintext> is text in a browser; drop it
    } else if (DROP_CONTENT.contains(name)) {
      if (!selfClosing) {
        dropping = name;
        dropDepth = 1;
      }
    } else if (ALLOWED.contains(name)) {
      if (VOID.contains(name)) {
        out.append('<').append(name).append('>');
      } else if (open.size() < MAX_DEPTH) {
        out.append('<').append(name);
        if (name.equals("a")) {
          String href = attrs.get("href");
          if (href != null && Escapers.isAllowedUrl(href)) {
            out.append(" href=\"");
            Escapers.escapeHtml(out, href);
            out.append('"').append(REL);
          }
        }
        out.append('>');
        open.add(name);
      }
    }
  }

  private void endTag(String name) {
    if (dropping != null) {
      if (name.equals(dropping) && --dropDepth == 0) {
        dropping = null;
      }
      return;
    }
    int i = open.lastIndexOf(name);
    if (i < 0) {
      return;
    }
    for (int j = open.size() - 1; j >= i; j--) {
      out.append("</").append(open.remove(j)).append('>');
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tokenizing

  /** Lower-cased (ASCII) tag name at {@code pos}, which is a letter. */
  private String tagName() {
    int start = pos;
    while (pos < n && !isSpace(s.charAt(pos)) && s.charAt(pos) != '/' && s.charAt(pos) != '>') {
      pos++;
    }
    return lower(s.substring(start, pos));
  }

  /**
   * Parses attributes up to and including the closing '>'; the first occurrence of a name wins,
   * values are entity-decoded. Returns {@code null} if the input ends inside the tag (a browser
   * drops such a tag).
   */
  private Map<String, String> attributes() {
    Map<String, String> attrs = new HashMap<>();
    selfClosing = false;
    while (true) {
      while (pos < n && (isSpace(s.charAt(pos)) || s.charAt(pos) == '/')) {
        selfClosing = s.charAt(pos) == '/' && pos + 1 < n && s.charAt(pos + 1) == '>';
        pos++;
      }
      if (pos >= n) {
        return null;
      }
      if (s.charAt(pos) == '>') {
        pos++;
        return attrs;
      }
      int start = pos++; // the first character may be '='
      while (pos < n && !isSpace(s.charAt(pos)) && "/>=".indexOf(s.charAt(pos)) < 0) {
        pos++;
      }
      String name = lower(s.substring(start, pos));
      while (pos < n && isSpace(s.charAt(pos))) {
        pos++;
      }
      String value = "";
      if (pos < n && s.charAt(pos) == '=') {
        pos++;
        while (pos < n && isSpace(s.charAt(pos))) {
          pos++;
        }
        if (pos >= n) {
          return null;
        }
        char q = s.charAt(pos);
        if (q == '"' || q == '\'') {
          int end = s.indexOf(q, pos + 1);
          if (end < 0) {
            pos = n;
            return null;
          }
          value = s.substring(pos + 1, end);
          pos = end + 1;
        } else {
          int vs = pos;
          while (pos < n && !isSpace(s.charAt(pos)) && s.charAt(pos) != '>') {
            pos++;
          }
          value = s.substring(vs, pos);
        }
      }
      if (!attrs.containsKey(name)) {
        attrs.put(name, decode(value));
      }
    }
  }

  /** Skips a comment at {@code pos}; {@code <!-->} and {@code <!--->} are complete comments. */
  private void skipComment() {
    if (s.startsWith("<!-->", pos)) {
      pos += 5;
    } else if (s.startsWith("<!--->", pos)) {
      pos += 6;
    } else {
      // One forward scan: searching separately for "-->" and "--!>" would rescan the rest of the
      // input for every comment that lacks one of them (quadratic).
      int i = pos + 4;
      while (true) {
        int dash = s.indexOf("--", i);
        if (dash < 0) {
          pos = n;
          return;
        }
        if (s.startsWith(">", dash + 2) || s.startsWith("!>", dash + 2)) {
          pos = s.indexOf('>', dash + 2) + 1;
          return;
        }
        i = dash + 1;
      }
    }
  }

  private void skipPast(char c, int from) {
    int end = s.indexOf(c, from);
    pos = end < 0 ? n : end + 1;
  }

  /** Skips the content and end tag of raw text element {@code name}; to the end if unclosed. */
  private void skipRawText(String name) {
    int i = pos;
    while (true) {
      int lt = s.indexOf("</", i);
      if (lt < 0) {
        pos = n;
        return;
      }
      int end = lt + 2 + name.length();
      if (end <= n
          && s.regionMatches(true, lt + 2, name, 0, name.length())
          && (end == n || isSpace(s.charAt(end)) || s.charAt(end) == '/' || s.charAt(end) == '>')) {
        skipPast('>', end);
        return;
      }
      i = lt + 2;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Text and character references

  /**
   * Writes {@code s[from, to)} as escaped text. Known character references are decoded and
   * re-escaped; an unknown named reference such as {@code &eacute;} is kept as is, since in text it
   * can only ever produce a character.
   */
  private void text(int from, int to) {
    if (dropping != null || from >= to) {
      return;
    }
    StringBuilder pending = new StringBuilder(to - from);
    int i = from;
    while (i < to) {
      char c = s.charAt(i);
      if (c != '&') {
        pending.append(c);
        i++;
        continue;
      }
      int end = reference(s, i, to, pending);
      if (end > 0) {
        i = end;
      } else if ((end = unknownNamed(s, i, to)) > 0) {
        Escapers.escapeHtml(out, pending.toString());
        pending.setLength(0);
        out.append(s, i, end);
        i = end;
      } else {
        pending.append('&');
        i++;
      }
    }
    Escapers.escapeHtml(out, pending.toString());
  }

  /** Decodes known character references in an attribute value; unknown ones stay literal. */
  private static String decode(String v) {
    if (v.indexOf('&') < 0) {
      return v;
    }
    StringBuilder sb = new StringBuilder(v.length());
    int i = 0;
    while (i < v.length()) {
      int end = v.charAt(i) == '&' ? reference(v, i, v.length(), sb) : -1;
      if (end > 0) {
        i = end;
      } else {
        sb.append(v.charAt(i++));
      }
    }
    return sb.toString();
  }

  /**
   * Decodes the numeric or known named reference at {@code s[i] == '&'} into {@code sb}; returns
   * the index after it, or -1 if there is none. Numeric references may omit the ';'; code points
   * that are zero, surrogates or out of range become U+FFFD.
   */
  private static int reference(String s, int i, int to, StringBuilder sb) {
    int j = i + 1;
    if (j < to && s.charAt(j) == '#') {
      j++;
      boolean hex = j < to && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
      if (hex) {
        j++;
      }
      int start = j;
      int cp = 0;
      for (; j < to; j++) {
        int d = digit(s.charAt(j));
        if (d < 0 || d >= (hex ? 16 : 10)) {
          break;
        }
        cp = Math.min(cp * (hex ? 16 : 10) + d, 0x110000);
      }
      if (j == start) {
        return -1;
      }
      if (j < to && s.charAt(j) == ';') {
        j++;
      }
      if (cp == 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
        cp = 0xFFFD;
      }
      sb.appendCodePoint(cp);
      return j;
    }
    int end = unknownNamed(s, i, to);
    if (end < 0) {
      return -1;
    }
    String value = ENTITIES.get(s.substring(i + 1, end - 1));
    if (value == null) {
      return -1;
    }
    sb.append(value);
    return end;
  }

  /** The index after a well-formed named reference {@code &[A-Za-z][A-Za-z0-9]*;} at i, or -1. */
  private static int unknownNamed(String s, int i, int to) {
    int j = i + 1;
    if (j >= to || !isAsciiLetter(s.charAt(j))) {
      return -1;
    }
    while (j < to && (isAsciiLetter(s.charAt(j)) || (s.charAt(j) >= '0' && s.charAt(j) <= '9'))) {
      j++;
    }
    return j < to && s.charAt(j) == ';' ? j + 1 : -1;
  }

  // ---------------------------------------------------------------------------------------------

  /** ASCII hex digit value, or -1. */
  private static int digit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }

  /** ASCII-only lower-casing, so {@code K} (Kelvin) never becomes {@code k}. */
  private static String lower(String name) {
    char[] cs = name.toCharArray();
    for (int i = 0; i < cs.length; i++) {
      if (cs[i] >= 'A' && cs[i] <= 'Z') {
        cs[i] = (char) (cs[i] + ('a' - 'A'));
      }
    }
    return new String(cs);
  }

  private static Set<String> set(String... names) {
    return new HashSet<>(Arrays.asList(names));
  }
}
