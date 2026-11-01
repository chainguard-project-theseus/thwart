package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sanitizer behind {@link SafeHtml#sanitize(String, HtmlPolicy)}. The input is tokenized and
 * the output rebuilt from scratch: it contains only tags the {@link HtmlPolicy} allows, written
 * here with attributes whose values were checked here, and text re-escaped by {@link Escapers}.
 * No input is copied through, so the result is safe however a browser would have parsed the
 * original: there are no comments, no raw-text or foreign-content elements, and no other
 * attributes.
 *
 * <p>The output is balanced {@code FLOW} content in a closed content model (#329, #592; the table
 * is in docs/context-model.md). Elements are kept on an open-element stack, and each start tag is
 * written only if it fits the current node:
 *
 * <ul>
 *   <li>the insertion point, {@code div}, {@code blockquote} and {@code li} hold phrasing elements
 *       and {@code p h1-h6 pre blockquote ul ol div hr};
 *   <li>{@code p}, headings, {@code pre} and phrasing elements hold only phrasing elements;
 *   <li>{@code ul} and {@code ol} hold only {@code li}, and {@code li} goes only there;
 *   <li>the {@link #FORMATTING} elements never go inside an open element of their own name.
 * </ul>
 *
 * <p>A start tag that doesn't fit, or that would nest deeper than {@link #MAX_DEPTH}, is
 * unwrapped: the tag is dropped and its content lands in the current node. Text is written in the
 * current node. An end tag closes up to its matching open element, unmatched end tags are ignored,
 * and everything still open is closed at the end. Nothing is implied, closed early or moved, so a
 * spec parser reads the output, without a parse error, as exactly the tree written; the sanitizer
 * doesn't model the tree builder. Code points the parser reports as errors become U+FFFD.
 */
final class HtmlSanitizer {
  /** Kept elements beyond this nesting depth are unwrapped. */
  private static final int MAX_DEPTH = 100;

  private static final Set<String> VOID = HtmlPolicy.unmodifiable("br", "hr", "img");

  /** Phrasing elements: may appear wherever phrasing content may. */
  private static final Set<String> PHRASING =
      HtmlPolicy.unmodifiable("a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "br", "img");

  /** Flow elements that are not phrasing (and are not {@code li}). */
  private static final Set<String> BLOCK =
      HtmlPolicy.unmodifiable("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "ul", "ol", "div", "hr");

  /**
   * Formatting elements, never inside an open element of their own name: that rules out the
   * parser's {@code a}-in-{@code a} error and its Noah's Ark clause (#329).
   */
  private static final Set<String> FORMATTING = HtmlPolicy.unmodifiable("a", "b", "code", "em", "i", "s", "small", "strong", "u");

  /** Raw text and RCDATA elements: skipped, content included, up to their end tag. */
  private static final Set<String> RAW_TEXT =
      HtmlPolicy.unmodifiable("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript");

  /** Dropped with everything inside, found by counting nested tags of the same name. */
  private static final Set<String> DROP_CONTENT = HtmlPolicy.unmodifiable("svg", "math", "object", "template", "select");

  /**
   * The spec's numeric character reference table for U+0080..U+009F (windows-1252), indexed by
   * {@code cp - 0x80}; 0 where the spec keeps the C1 control, which the writers make U+FFFD. Word
   * pastes such as {@code don&#146;t} then read as the browser shows them (#592).
   */
  private static final String WINDOWS_1252 = "€\0‚ƒ„…†‡ˆ‰Š‹Œ\0Ž\0"
      + "\0‘’“”•–—˜™š›œ\0žŸ";

  private static final Map<String, String> ENTITIES = new HashMap<>();

  static {
    String[] pairs = {
      "amp", "&", "AMP", "&", "lt", "<", "LT", "<", "gt", ">", "GT", ">", "quot", "\"",
      "QUOT", "\"", "apos", "'", "nbsp", " ", "Tab", "\t", "NewLine", "\n", "colon", ":",
      "sol", "/", "lpar", "(", "rpar", ")", "period", ".", "comma", ",", "excl", "!",
      "quest", "?", "num", "#", "lbrace", "{", "lcub", "{", "rbrace", "}", "rcub", "}", "semi", ";", "equals", "=", "copy", "©", "reg", "®",
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

  /**
   * The HTML 4 named references, which Spring's {@code HtmlUtils.htmlEscape} writes (#262). With
   * its ';', each is a complete name in the spec's table, so the parser reads it without an error
   * and the sanitizer keeps it as written; any other name not in {@link #ENTITIES} is escaped (#592).
   */
  private static final Set<String> HTML4_NAMES = HtmlPolicy.unmodifiable(
      "Aacute", "aacute", "Acirc", "acirc", "acute", "AElig", "aelig", "Agrave", "agrave", "alefsym", "Alpha", "alpha",
      "amp", "and", "ang", "Aring", "aring", "asymp", "Atilde", "atilde", "Auml", "auml", "bdquo", "Beta", "beta",
      "brvbar", "bull", "cap", "Ccedil", "ccedil", "cedil", "cent", "Chi", "chi", "circ", "clubs", "cong", "copy",
      "crarr", "cup", "curren", "Dagger", "dagger", "dArr", "darr", "deg", "Delta", "delta", "diams", "divide",
      "Eacute", "eacute", "Ecirc", "ecirc", "Egrave", "egrave", "empty", "emsp", "ensp", "Epsilon", "epsilon",
      "equiv", "Eta", "eta", "ETH", "eth", "Euml", "euml", "euro", "exist", "fnof", "forall", "frac12", "frac14",
      "frac34", "frasl", "Gamma", "gamma", "ge", "gt", "hArr", "harr", "hearts", "hellip", "Iacute", "iacute",
      "Icirc", "icirc", "iexcl", "Igrave", "igrave", "image", "infin", "int", "Iota", "iota", "iquest", "isin",
      "Iuml", "iuml", "Kappa", "kappa", "Lambda", "lambda", "lang", "laquo", "lArr", "larr", "lceil", "ldquo", "le",
      "lfloor", "lowast", "loz", "lrm", "lsaquo", "lsquo", "lt", "macr", "mdash", "micro", "middot", "minus", "Mu",
      "mu", "nabla", "nbsp", "ndash", "ne", "ni", "not", "notin", "nsub", "Ntilde", "ntilde", "Nu", "nu", "Oacute",
      "oacute", "Ocirc", "ocirc", "OElig", "oelig", "Ograve", "ograve", "oline", "Omega", "omega", "Omicron",
      "omicron", "oplus", "or", "ordf", "ordm", "Oslash", "oslash", "Otilde", "otilde", "otimes", "Ouml", "ouml",
      "para", "part", "permil", "perp", "Phi", "phi", "Pi", "pi", "piv", "plusmn", "pound", "Prime", "prime", "prod",
      "prop", "Psi", "psi", "quot", "radic", "rang", "raquo", "rArr", "rarr", "rceil", "rdquo", "real", "reg",
      "rfloor", "Rho", "rho", "rlm", "rsaquo", "rsquo", "sbquo", "Scaron", "scaron", "sdot", "sect", "shy", "Sigma",
      "sigma", "sigmaf", "sim", "spades", "sub", "sube", "sum", "sup", "sup1", "sup2", "sup3", "supe", "szlig", "Tau",
      "tau", "there4", "Theta", "theta", "thetasym", "thinsp", "THORN", "thorn", "tilde", "times", "trade", "Uacute",
      "uacute", "uArr", "uarr", "Ucirc", "ucirc", "Ugrave", "ugrave", "uml", "upsih", "Upsilon", "upsilon", "Uuml",
      "uuml", "weierp", "Xi", "xi", "Yacute", "yacute", "yen", "Yuml", "yuml", "Zeta", "zeta", "zwj", "zwnj");

  private final HtmlPolicy policy;
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

  /** Output length right after a {@code <pre>} start tag, or -1. */
  private int afterPre = -1;

  private HtmlSanitizer(String s, HtmlPolicy policy) {
    // A browser normalizes newlines before it tokenizes; doing the same keeps the text the parser
    // sees equal to the text written here (a leading LF after <pre> is dropped by the parser).
    this.s = s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    this.n = this.s.length();
    this.out = new StringBuilder(n + 16);
    this.policy = policy;
  }

  static String sanitize(String html, HtmlPolicy policy) {
    HtmlSanitizer p = new HtmlSanitizer(html, policy);
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
    while (!open.isEmpty()) {
      pop();
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
      if (HtmlPolicy.isAsciiLetter(c)) {
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
    if (!HtmlPolicy.isAsciiLetter(next)) {
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
    } else if (policy.elements.contains(name)) {
      String attributes = attributes(name, attrs);
      if (attributes != null && fits(name)) {
        out.append('<').append(name).append(attributes).append('>');
        if (!VOID.contains(name)) {
          open.add(name);
        }
        if (name.equals("pre")) {
          afterPre = out.length();
        }
      }
    }
  }

  /**
   * Whether start tag {@code name} is written in the current node: it fits the content model there
   * (see the class comment), it isn't a {@link #FORMATTING} element inside one of its own name, and
   * it stays within {@link #MAX_DEPTH}. Otherwise it is unwrapped.
   */
  private boolean fits(String name) {
    if (FORMATTING.contains(name) && open.contains(name)) {
      return false;
    }
    if (!VOID.contains(name) && open.size() >= MAX_DEPTH) {
      return false;
    }
    String parent = open.isEmpty() ? null : open.get(open.size() - 1);
    if (parent == null || parent.equals("div") || parent.equals("blockquote") || parent.equals("li")) {
      return PHRASING.contains(name) || BLOCK.contains(name);
    }
    if (parent.equals("ul") || parent.equals("ol")) {
      return name.equals("li");
    }
    return PHRASING.contains(name); // p, h1-h6, pre and the phrasing elements
  }

  private void pop() {
    out.append("</").append(open.remove(open.size() - 1)).append('>');
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
    while (open.size() > i) {
      pop();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Attributes

  /**
   * The attributes written on {@code name}: those the policy allows, with checked values, in a
   * fixed order. Returns {@code null} if the element must be dropped (an {@code img} without an
   * acceptable {@code src}).
   */
  private String attributes(String name, Map<String, String> attrs) {
    StringBuilder sb = new StringBuilder();
    Set<String> allowed = policy.attributes;
    if (allowed.contains("id")) {
      String id = attrs.get("id");
      if (id != null) {
        id = HtmlPolicy.stripWhitespace(decode(id));
        if (HtmlPolicy.isClassToken(id)) {
          // an id that already has the prefix keeps it, so sanitizing twice changes nothing
          sb.append(" id=\"").append(id.startsWith(policy.idPrefix) ? "" : policy.idPrefix).append(id).append('"');
        }
      }
    }
    if (policy.allowsClasses() && attrs.get("class") != null) {
      StringBuilder classes = new StringBuilder();
      for (String token : decode(attrs.get("class")).split("[ \t\n\f]+")) {
        if (policy.allowsClass(token)) {
          classes.append(classes.length() == 0 ? "" : " ").append(token);
        }
      }
      if (classes.length() > 0) {
        sb.append(" class=\"").append(classes).append('"');
      }
    }
    String link = null;
    if (name.equals("a")) {
      link = url(attrs.get("href"), false);
      if (link != null) {
        sb.append(" href=\"").append(link).append('"');
      }
    } else if (name.equals("img")) {
      String src = url(attrs.get("src"), true);
      if (src == null) {
        return null;
      }
      sb.append(" src=\"").append(src).append('"');
      textAttribute(sb, "alt", attrs);
    }
    textAttribute(sb, "title", attrs);
    if (name.equals("img")) {
      numberAttribute(sb, "width", attrs, 5, false);
      numberAttribute(sb, "height", attrs, 5, false);
    } else if (name.equals("ol")) {
      numberAttribute(sb, "start", attrs, 9, true);
      if (allowed.contains("reversed") && attrs.containsKey("reversed")) {
        sb.append(" reversed");
      }
    } else if (name.equals("li")) {
      numberAttribute(sb, "value", attrs, 9, true);
    }
    if (link != null) {
      sb.append(policy.rel);
      String target = policy.linkTarget ? attrs.get("target") : null;
      if (target != null && HtmlPolicy.lower(HtmlPolicy.stripWhitespace(decode(target))).equals("_blank")) {
        sb.append(" target=\"_blank\"");
      }
    }
    return sb.toString();
  }

  /**
   * The escaped value of a URL attribute that passes the scheme check, or {@code null}. An image
   * {@code src} must also be a same-origin path unless the policy allows remote images, or a base64
   * {@code data:image/...} URL if the policy allows those.
   */
  private String url(String raw, boolean image) {
    if (raw == null) {
      return null;
    }
    String url = decode(raw);
    boolean data = image && policy.dataImages && isDataImage(url);
    if (!data && !Escapers.isAllowedUrl(url)) {
      return null;
    }
    if (image && !data && !policy.remoteImages && !(url.startsWith("/") && Escapers.isSameOriginUrl(url))) {
      return null; // not a same-origin path: '/' not followed by '/' or '\' (tab, CR, LF ignored)
    }
    if (!image && policy.attributes.contains("id") && url.length() > 1 && url.charAt(0) == '#'
        && HtmlPolicy.isClassToken(url.substring(1)) && !url.startsWith(policy.idPrefix, 1)) {
      url = "#" + policy.idPrefix + url.substring(1); // follow the prefixed id
    }
    return Escapers.escapeHtml(replaceForbidden(url)); // after the checks: U+FFFD is inert in a URL
  }

  private static boolean isDataImage(String url) {
    String u = HtmlPolicy.lower(url);
    String[] types = {"data:image/png;base64,", "data:image/gif;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,"};
    for (String t : types) {
      if (u.startsWith(t)) {
        for (int i = t.length(); i < u.length(); i++) {
          char c = u.charAt(i);
          if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=')) {
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Writes a text attribute the policy allows. Known references are decoded and re-escaped, and
   * {@link #HTML4_NAMES} references are kept; any other {@code &} is escaped, since an unknown
   * name such as {@code &X;} is a parse error in a value too (#592).
   */
  private void textAttribute(StringBuilder sb, String name, Map<String, String> attrs) {
    String raw = policy.attributes.contains(name) ? attrs.get(name) : null;
    if (raw == null) {
      return;
    }
    sb.append(' ').append(name).append("=\"");
    StringBuilder pending = new StringBuilder(raw.length());
    int i = 0;
    while (i < raw.length()) {
      char c = raw.charAt(i);
      int end = c == '&' ? reference(raw, i, raw.length(), pending) : -1;
      if (end > 0) {
        i = end;
      } else if (c == '&' && (end = html4Named(raw, i, raw.length())) > 0) {
        Escapers.appendHtml(sb, replaceForbidden(pending.toString()));
        pending.setLength(0);
        sb.append(raw, i, end);
        i = end;
      } else {
        pending.append(c);
        i++;
      }
    }
    Escapers.appendHtml(sb, replaceForbidden(pending.toString()));
    sb.append('"');
  }

  /** Writes a numeric attribute the policy allows: up to {@code digits} ASCII digits, optionally negative. */
  private void numberAttribute(StringBuilder sb, String name, Map<String, String> attrs, int digits, boolean signed) {
    String raw = policy.attributes.contains(name) ? attrs.get(name) : null;
    if (raw == null) {
      return;
    }
    String v = HtmlPolicy.stripWhitespace(decode(raw));
    int start = signed && v.startsWith("-") ? 1 : 0;
    if (v.length() == start || v.length() - start > digits) {
      return;
    }
    for (int i = start; i < v.length(); i++) {
      if (v.charAt(i) < '0' || v.charAt(i) > '9') {
        return;
      }
    }
    sb.append(' ').append(name).append("=\"").append(v).append('"');
  }

  // ---------------------------------------------------------------------------------------------
  // Tokenizing

  /** Lower-cased (ASCII) tag name at {@code pos}, which is a letter. */
  private String tagName() {
    int start = pos;
    while (pos < n && !HtmlPolicy.isWhitespace(s.charAt(pos)) && s.charAt(pos) != '/' && s.charAt(pos) != '>') {
      pos++;
    }
    return HtmlPolicy.lower(s.substring(start, pos));
  }

  /**
   * Parses attributes up to and including the closing '>'; the first occurrence of a name wins,
   * values are raw (decoded where they are used). Returns {@code null} if the input ends inside the tag (a browser
   * drops such a tag).
   */
  private Map<String, String> attributes() {
    Map<String, String> attrs = new HashMap<>();
    selfClosing = false;
    while (true) {
      while (pos < n && (HtmlPolicy.isWhitespace(s.charAt(pos)) || s.charAt(pos) == '/')) {
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
      while (pos < n && !HtmlPolicy.isWhitespace(s.charAt(pos)) && "/>=".indexOf(s.charAt(pos)) < 0) {
        pos++;
      }
      String name = HtmlPolicy.lower(s.substring(start, pos));
      while (pos < n && HtmlPolicy.isWhitespace(s.charAt(pos))) {
        pos++;
      }
      String value = "";
      if (pos < n && s.charAt(pos) == '=') {
        pos++;
        while (pos < n && HtmlPolicy.isWhitespace(s.charAt(pos))) {
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
          while (pos < n && !HtmlPolicy.isWhitespace(s.charAt(pos)) && s.charAt(pos) != '>') {
            pos++;
          }
          value = s.substring(vs, pos);
        }
      }
      if (!attrs.containsKey(name)) {
        attrs.put(name, value);
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
          && HtmlPolicy.lower(s.substring(lt + 2, end)).equals(name)
          && (end == n || HtmlPolicy.isWhitespace(s.charAt(end)) || s.charAt(end) == '/' || s.charAt(end) == '>')) {
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
   * re-escaped, and {@link #HTML4_NAMES} references such as {@code &eacute;} are kept as they are.
   * Any other {@code &} is escaped, so a named reference such as {@code &notit;} or {@code &X;}
   * comes out as {@code &amp;notit;}: copied as written, the parser would report a parse error for
   * it (a legacy name such as {@code &not} without its ';', or an unknown name; #592). The
   * references for braces are known, so every brace goes through the text escaper, which breaks
   * {@code {{} as {@link Escapers#text} does (#248).
   */
  private void text(int from, int to) {
    if (dropping != null || from >= to) {
      return;
    }
    int start = out.length();
    writeText(from, to);
    if (start == afterPre && out.length() > start && out.charAt(start) == '\n') {
      out.insert(start, '\n'); // the parser drops one newline right after <pre>
    }
  }

  private void writeText(int from, int to) {
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
      } else if ((end = html4Named(s, i, to)) > 0) {
        Escapers.appendText(out, replaceForbidden(pending.toString()));
        pending.setLength(0);
        out.append(s, i, end);
        i = end;
      } else {
        pending.append('&');
        i++;
      }
    }
    Escapers.appendText(out, replaceForbidden(pending.toString()));
  }

  /**
   * {@code s} with every code point the parser reports as a parse error in its input (a control
   * other than ASCII whitespace, a noncharacter, a lone surrogate) replaced by U+FFFD, and CR
   * (here only a reference such as {@code &#13;} makes one) written as the LF the parser reads it
   * as (#592). The text and attribute writers call it, so the output parses without an error.
   */
  private static String replaceForbidden(String s) {
    StringBuilder sb = null;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      int next = i + Character.charCount(cp);
      boolean forbidden = (cp < 0x20 && cp != '\t' && cp != '\n' && cp != '\f' && cp != '\r')
          || (cp >= 0x7F && cp <= 0x9F) || (cp >= 0xFDD0 && cp <= 0xFDEF) || (cp & 0xFFFE) == 0xFFFE
          || (cp >= 0xD800 && cp <= 0xDFFF);
      if (forbidden || cp == '\r') {
        if (sb == null) {
          sb = new StringBuilder(s.length()).append(s, 0, i);
        }
        sb.append(forbidden ? '\uFFFD' : '\n');
      } else if (sb != null) {
        sb.append(s, i, next);
      }
      i = next;
    }
    return sb == null ? s : sb.toString();
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
      } else if (cp >= 0x80 && cp <= 0x9F && WINDOWS_1252.charAt(cp - 0x80) != 0) {
        cp = WINDOWS_1252.charAt(cp - 0x80); // as a browser reads it; unmapped C1 become U+FFFD later
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

  /** The index after an {@link #HTML4_NAMES} reference with its ';' at i, or -1. */
  private static int html4Named(String s, int i, int to) {
    int end = unknownNamed(s, i, to);
    return end > 0 && HTML4_NAMES.contains(s.substring(i + 1, end - 1)) ? end : -1;
  }

  /** The index after a well-formed named reference {@code &[A-Za-z][A-Za-z0-9]*;} at i, or -1. */
  private static int unknownNamed(String s, int i, int to) {
    int j = i + 1;
    if (j >= to || !HtmlPolicy.isAsciiLetter(s.charAt(j))) {
      return -1;
    }
    while (j < to && (HtmlPolicy.isAsciiLetter(s.charAt(j)) || (s.charAt(j) >= '0' && s.charAt(j) <= '9'))) {
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
}
