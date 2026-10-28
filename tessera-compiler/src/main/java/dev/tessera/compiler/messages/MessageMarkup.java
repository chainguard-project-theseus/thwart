package dev.tessera.compiler.messages;

import dev.tessera.compiler.Backports;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The build-time check for a bundle message rendered as HTML ({@code t:utext="#{key}"}, #236).
 * The message is developer-authored markup, so like template markup it is checked here rather
 * than sanitized at runtime. The grammar is deliberately strict and small, so that there is no
 * room for this check and a browser to read the same text differently:
 *
 * <ul>
 *   <li>Elements: {@link #ELEMENTS} only. Comments, doctypes, CDATA and processing instructions
 *       are rejected. A {@code <} that cannot start a tag (not followed by an ASCII letter,
 *       {@code /}, {@code !} or {@code ?}) is text, as in a browser. A message may not end with
 *       {@code <}, which the template text after it would continue.
 *   <li>Attributes: {@link #GLOBAL_ATTRIBUTES} on every element, plus {@link #LINK_ATTRIBUTES} on
 *       {@code a}; values must be quoted. An {@code href} must be relative or
 *       {@code http}/{@code https}/{@code mailto}/{@code tel}, with no whitespace or control
 *       characters inside it and no character references other than {@code &amp;}. ASCII
 *       whitespace at either end is allowed: the browser's URL parser strips it, so the scheme is
 *       checked on the stripped value (#279).
 *   <li>Balance: every end tag matches an open element, and every element is closed, except
 *       {@link #IMPLIED_END} ones, whose end tag HTML lets you leave out. {@code <p/>} is a
 *       {@code <p>}, as in a browser.
 * </ul>
 *
 * <p>The message is checked twice: as written (Spring returns a message without arguments
 * unformatted) and as {@link java.text.MessageFormat} produces it (quotes removed, each
 * placeholder an escaped argument). Placeholders must be simple ({@code {0}}, {@code {0,number}},
 * {@code {0,date,short}}, ...) and may only stand in text, never inside a tag, so an argument
 * cannot reach an attribute or a URL.
 */
final class MessageMarkup {
  private MessageMarkup() {}

  /** Stands for a formatted argument in the MessageFormat view of a message. */
  static final char ARG = '';

  static final Set<String> ELEMENTS =
      Backports.setOf(
          "a", "abbr", "b", "blockquote", "br", "cite", "code", "dd", "del", "div", "dl", "dt", "em",
          "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "ins", "kbd", "li", "mark", "ol", "p", "pre",
          "q", "s", "small", "span", "strong", "sub", "sup", "u", "ul");

  private static final Set<String> VOID = Backports.setOf("br", "hr");

  private static final Set<String> IMPLIED_END = Backports.setOf("p", "li", "dt", "dd");

  static final Set<String> GLOBAL_ATTRIBUTES = Backports.setOf("class", "id", "title", "lang", "dir");

  static final Set<String> LINK_ATTRIBUTES = Backports.setOf("href", "target", "rel", "hreflang");

  private static final Set<String> NUMBER_STYLES = Backports.setOf("integer", "currency", "percent");
  private static final Set<String> DATE_STYLES = Backports.setOf("short", "medium", "long", "full");

  /** Why the message cannot be rendered as HTML, or {@code null} if it can. */
  static String problem(String message) {
    try {
      check(message);
    } catch (Bad e) {
      return e.getMessage();
    }
    try {
      check(formatted(message));
      return null;
    } catch (Bad e) {
      return e.getMessage()
          + " (in the message as MessageFormat formats it, where ' quotes text: write '' for an apostrophe"
          + " and use double quotes around attribute values)";
    }
  }

  static final class Bad extends Exception {
    private static final long serialVersionUID = 1L;

    Bad(String message) {
      super(message, null, false, false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // MessageFormat view

  /** The message as MessageFormat outputs it, with {@link #ARG} for each placeholder. */
  static String formatted(String s) throws Bad {
    StringBuilder sb = new StringBuilder(s.length());
    int n = s.length();
    int i = 0;
    while (i < n) {
      char c = s.charAt(i);
      if (c == '\'') {
        if (i + 1 < n && s.charAt(i + 1) == '\'') {
          sb.append('\'');
          i += 2;
          continue;
        }
        i++;
        while (i < n) { // quoted literal, to the next lone quote
          if (s.charAt(i) == '\'') {
            if (i + 1 < n && s.charAt(i + 1) == '\'') {
              sb.append('\'');
              i += 2;
              continue;
            }
            i++;
            break;
          }
          sb.append(s.charAt(i++));
        }
      } else if (c == '{') {
        int close = s.indexOf('}', i);
        if (close < 0) {
          throw new Bad("'{' at offset " + i + " has no closing '}'");
        }
        checkPlaceholder(s.substring(i + 1, close));
        sb.append(ARG);
        i = close + 1;
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  private static void checkPlaceholder(String spec) throws Bad {
    String[] parts = spec.split(",", -1);
    boolean ok = parts.length <= 3 && parts[0].trim().matches("[0-9]+");
    if (ok && parts.length >= 2) {
      String type = parts[1].trim();
      Set<String> styles =
          type.equals("number") ? NUMBER_STYLES : type.equals("date") || type.equals("time") ? DATE_STYLES : null;
      ok = styles != null && (parts.length == 2 || styles.contains(parts[2].trim()));
    }
    if (!ok) {
      throw new Bad(
          "placeholder {" + spec + "} is not allowed in a message rendered as HTML: use {n}, {n,number},"
              + " {n,number,integer|currency|percent}, or {n,date|time[,short|medium|long|full]}");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Markup

  private static void check(String s) throws Bad {
    List<String> open = new ArrayList<>();
    int n = s.length();
    int i = 0;
    while (i < n) {
      if (s.charAt(i) != '<') {
        i++;
        continue;
      }
      char next = i + 1 < n ? s.charAt(i + 1) : 0;
      if (isLetter(next)) {
        i = startTag(s, i, open);
      } else if (next == '/') {
        i = endTag(s, i, open);
      } else if (next == '!' || next == '?') {
        throw new Bad("comments, doctypes and CDATA are not allowed");
      } else if (i + 1 == n) {
        // The template text after the message would continue this '<' into a tag.
        throw new Bad("a message may not end with '<': write &lt; for a literal '<'");
      } else if (next == ARG) {
        // An argument starting with a letter would turn this '<' into a tag.
        throw new Bad("a placeholder may not follow '<' directly: write &lt; for a literal '<'");
      } else {
        i++; // a '<' that starts no markup is text
      }
    }
    for (String name : open) {
      if (!IMPLIED_END.contains(name)) {
        throw new Bad("<" + name + "> is never closed");
      }
    }
  }

  /** Checks the start tag at {@code lt} and returns the offset after it. */
  private static int startTag(String s, int lt, List<String> open) throws Bad {
    int n = s.length();
    int j = lt + 1;
    while (j < n && isLetterOrDigit(s.charAt(j))) {
      j++;
    }
    String name = lower(s.substring(lt + 1, j));
    if (j < n && !isSpace(s.charAt(j)) && s.charAt(j) != '/' && s.charAt(j) != '>') {
      throw new Bad("malformed tag <" + s.substring(lt + 1, j + 1));
    }
    if (!ELEMENTS.contains(name)) {
      throw new Bad("<" + name + "> is not allowed (allowed: " + String.join(" ", ELEMENTS) + ")");
    }
    while (true) {
      int before = j;
      while (j < n && isSpace(s.charAt(j))) {
        j++;
      }
      if (j >= n) {
        throw new Bad("<" + name + "> is not closed with '>'");
      }
      if (s.charAt(j) == '>' || s.startsWith("/>", j)) {
        j += s.charAt(j) == '>' ? 1 : 2;
        break;
      }
      if (j == before) {
        throw new Bad("attributes of <" + name + "> must be separated by whitespace");
      }
      int nameStart = j;
      if (!isLetter(s.charAt(j))) {
        throw new Bad("malformed attribute in <" + name + ">");
      }
      while (j < n && (isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '-')) {
        j++;
      }
      String attr = lower(s.substring(nameStart, j));
      if (!GLOBAL_ATTRIBUTES.contains(attr) && !(name.equals("a") && LINK_ATTRIBUTES.contains(attr))) {
        throw new Bad("attribute " + attr + " is not allowed on <" + name + ">");
      }
      if (j + 1 >= n || s.charAt(j) != '=' || (s.charAt(j + 1) != '"' && s.charAt(j + 1) != '\'')) {
        throw new Bad("attribute " + attr + " of <" + name + "> needs a quoted value, as in " + attr + "=\"...\"");
      }
      char quote = s.charAt(j + 1);
      int end = s.indexOf(quote, j + 2);
      if (end < 0) {
        throw new Bad("the value of attribute " + attr + " of <" + name + "> is not closed");
      }
      String value = s.substring(j + 2, end);
      if (value.indexOf(ARG) >= 0) {
        throw new Bad("a placeholder may not be inside a tag (attribute " + attr + " of <" + name + ">):"
            + " arguments are escaped text");
      }
      if (attr.equals("href")) {
        checkUrl(value);
      }
      j = end + 1;
    }
    if (!VOID.contains(name)) {
      open.add(name);
    }
    return j;
  }

  /** Checks the end tag at {@code lt} and returns the offset after it. */
  private static int endTag(String s, int lt, List<String> open) throws Bad {
    int n = s.length();
    int j = lt + 2;
    while (j < n && isLetterOrDigit(s.charAt(j))) {
      j++;
    }
    String name = lower(s.substring(lt + 2, j));
    while (j < n && isSpace(s.charAt(j))) {
      j++;
    }
    if (name.isEmpty() || j >= n || s.charAt(j) != '>') {
      throw new Bad("malformed end tag " + s.substring(lt, Math.min(n, j + 1)));
    }
    int match = open.lastIndexOf(name);
    if (match < 0) {
      throw new Bad("</" + name + "> has no matching start tag in the message");
    }
    for (int k = open.size() - 1; k > match; k--) {
      if (!IMPLIED_END.contains(open.get(k))) {
        throw new Bad("</" + name + "> closes <" + open.get(k) + ">, which is still open");
      }
    }
    while (open.size() > match) {
      open.remove(open.size() - 1);
    }
    return j + 1;
  }

  /**
   * Checks an {@code href}. Leading and trailing ASCII whitespace is stripped first, as the
   * browser does: the WHATWG URL parser strips leading and trailing C0 controls and spaces, a
   * superset of what is stripped here, so the scheme check sees the URL the browser uses. Any
   * whitespace or control character left inside is rejected, because the browser removes tab and
   * newline anywhere in a URL ({@code java\tscript:} is {@code javascript:}).
   */
  private static void checkUrl(String value) throws Bad {
    if (value.replace("&amp;", "").indexOf('&') >= 0) {
      throw new Bad("href \"" + value + "\": the only character reference allowed in a URL is &amp;");
    }
    String url = value.replace("&amp;", "&");
    int from = 0;
    int to = url.length();
    while (from < to && isSpace(url.charAt(from))) {
      from++;
    }
    while (to > from && isSpace(url.charAt(to - 1))) {
      to--;
    }
    url = url.substring(from, to);
    for (int k = 0; k < url.length(); k++) {
      char c = url.charAt(k);
      if (c <= ' ' || c == 0x7f) {
        throw new Bad("href \"" + value + "\" contains whitespace or a control character");
      }
    }
    for (int k = 0; k < url.length(); k++) {
      char c = url.charAt(k);
      if (c == '/' || c == '?' || c == '#') {
        return;
      }
      if (c == ':') {
        String scheme = lower(url.substring(0, k));
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel")) {
          return;
        }
        throw new Bad("href \"" + value + "\": only http, https, mailto, tel and relative URLs are allowed");
      }
    }
  }

  private static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isLetterOrDigit(char c) {
    return isLetter(c) || (c >= '0' && c <= '9');
  }

  /**
   * {@code s} with only A-Z lower-cased, as a browser folds names and schemes ({@code toLowerCase}
   * also folds U+212A and U+0130). TODO(#506): use HtmlParser.asciiLower once it is public.
   */
  private static String lower(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return sb.toString();
  }

  /** HTML whitespace. */
  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }
}
