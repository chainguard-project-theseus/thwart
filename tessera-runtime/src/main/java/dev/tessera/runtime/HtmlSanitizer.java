package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>The output is balanced {@code FLOW} content (#329). Elements are kept on an open-element
 * stack, and each is placed only where a browser's tree builder keeps it as written:
 *
 * <ul>
 *   <li>{@code p}, headings, {@code pre} and phrasing elements hold only phrasing elements; a
 *       block element first closes them, as a browser's implied end tags would.
 *   <li>{@code a} never nests in {@code a}; {@code li} only in {@code ul}/{@code ol}. No other
 *       formatting element nests in one of its own name either (the inner tag is dropped, its text
 *       kept): two identical ones, with one more around the insertion point, would make a browser
 *       drop that one from its list of active formatting elements (the Noah's Ark clause).
 *   <li>Table parts only inside a {@code table}, with the {@code tbody} and {@code tr} a browser
 *       would imply written out; no text, and nothing else, directly in a table, section or row.
 *   <li>An end tag closes up to its matching open element, unmatched end tags are ignored, and
 *       everything still open is closed at the end.
 * </ul>
 *
 * <p>A start tag that fits nowhere before a table cell, caption, table part or list is dropped
 * (its text is kept). So the output never implies an end tag, never moves content (foster
 * parenting, formatting reconstruction), and cannot close the page's own elements.
 */
final class HtmlSanitizer {
  /** Kept elements beyond this nesting depth are dropped (their text is kept). */
  private static final int MAX_DEPTH = 100;

  private static final Set<String> VOID = set("br", "hr", "img");

  /** Phrasing elements: may appear wherever phrasing content may. */
  private static final Set<String> PHRASING =
      set("a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "br", "img");

  /** Flow elements that are not phrasing (and are not {@code li} or table parts). */
  private static final Set<String> BLOCK =
      set("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "ul", "ol", "div", "hr", "table");

  /** Elements whose content is phrasing only, so that no block start tag closes them implicitly. */
  private static final Set<String> PHRASING_CONTAINERS =
      set("a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre");

  private static final Set<String> TABLE_SECTIONS = set("thead", "tbody", "tfoot");

  /** Formatting elements that are dropped (text kept) inside an open element of the same name. */
  private static final Set<String> NO_SELF_NESTING = set("b", "code", "em", "i", "s", "small", "strong", "u");

  /** Open elements a start tag never closes implicitly; past them a tag that doesn't fit is dropped. */
  private static final Set<String> BOUNDARY =
      set("ul", "ol", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th");

  /** Where text would be foster-parented or ignored: it is dropped. */
  private static final Set<String> NO_TEXT = set("table", "thead", "tbody", "tfoot", "tr");

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
    } else if (policy.elements.contains(name)) {
      String attributes = attributes(name, attrs);
      if (attributes != null && place(name)) {
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
   * Closes open elements until {@code name} fits as a child of the current node, and writes the
   * {@code tbody}/{@code tr} a browser would imply before a table part. Returns false (nothing
   * changed) if it fits nowhere, or would be nested deeper than {@link #MAX_DEPTH}.
   */
  private boolean place(String name) {
    if (name.equals("caption") || TABLE_SECTIONS.contains(name) || name.equals("tr")
        || name.equals("td") || name.equals("th")) {
      int table = open.lastIndexOf("table");
      if (table < 0) {
        return false;
      }
      // As in a browser, a table part closes the cell, row or section it is in.
      int keep = open.size();
      while (!fitsTable(open.get(keep - 1), name)) {
        keep--;
      }
      String cur = open.get(keep - 1);
      List<String> implied = new ArrayList<>(2);
      if (cur.equals("table") && (name.equals("tr") || name.equals("td") || name.equals("th"))) {
        implied.add("tbody");
      }
      if (!cur.equals("tr") && (name.equals("td") || name.equals("th"))) {
        implied.add("tr");
      }
      if (keep + implied.size() + (VOID.contains(name) ? 0 : 1) > MAX_DEPTH) {
        return false;
      }
      while (open.size() > keep) {
        pop();
      }
      for (String e : implied) {
        out.append('<').append(e).append('>');
        open.add(e);
      }
      return true;
    }
    if (NO_SELF_NESTING.contains(name) && open.contains(name)) {
      // Nested in itself it would be a second identical entry in a browser's list of active
      // formatting elements; with one more around the insertion point, a third would drop the
      // outermost one (the Noah's Ark clause), which changes the parser state (#329).
      return false;
    }
    int keep = open.size();
    if (name.equals("a")) {
      // A browser closes an open <a> before another (the adoption agency); so do we.
      for (int i = open.size() - 1; i >= 0 && !BOUNDARY.contains(open.get(i)); i--) {
        if (open.get(i).equals("a")) {
          keep = i;
        }
      }
    }
    while (!fits(keep == 0 ? null : open.get(keep - 1), name)) {
      if (keep == 0 || BOUNDARY.contains(open.get(keep - 1))) {
        return false;
      }
      keep--;
    }
    if (!VOID.contains(name) && keep >= MAX_DEPTH) {
      return false;
    }
    while (open.size() > keep) {
      pop();
    }
    return true;
  }

  /** Whether {@code child} (not a table part) is kept as written inside {@code parent} ({@code null}: the insertion point). */
  private static boolean fits(String parent, String child) {
    if (parent == null) {
      return PHRASING.contains(child) || BLOCK.contains(child);
    }
    if (parent.equals("ul") || parent.equals("ol")) {
      return child.equals("li");
    }
    if (PHRASING_CONTAINERS.contains(parent)) {
      return PHRASING.contains(child);
    }
    if (NO_TEXT.contains(parent)) {
      return false;
    }
    // div, blockquote, li, td, th, caption: flow content
    return PHRASING.contains(child) || BLOCK.contains(child);
  }

  /** Whether table part {@code child} goes directly into {@code parent}, with implied {@code tbody}/{@code tr}. */
  private static boolean fitsTable(String parent, String child) {
    if (child.equals("td") || child.equals("th")) {
      return parent.equals("tr") || TABLE_SECTIONS.contains(parent) || parent.equals("table");
    }
    if (child.equals("tr")) {
      return TABLE_SECTIONS.contains(parent) || parent.equals("table");
    }
    return parent.equals("table");
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
        id = decode(id).trim();
        if (HtmlPolicy.isClassToken(id)) {
          // an id that already has the prefix keeps it, so sanitizing twice changes nothing
          sb.append(" id=\"").append(id.startsWith(policy.idPrefix) ? "" : policy.idPrefix).append(id).append('"');
        }
      }
    }
    if (policy.allowsClasses() && attrs.get("class") != null) {
      StringBuilder classes = new StringBuilder();
      for (String token : decode(attrs.get("class")).split("[ \t\n\f]+")) {
        if (HtmlPolicy.isClassToken(token)
            && !HtmlPolicy.isAngularClass(token)
            && policy.allowsClass(token)) {
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
    } else if (name.equals("td") || name.equals("th")) {
      numberAttribute(sb, "colspan", attrs, 4, false);
      numberAttribute(sb, "rowspan", attrs, 5, false);
      String align = allowed.contains("align") ? attrs.get("align") : null;
      if (align != null) {
        align = HtmlPolicy.lower(decode(align).trim());
        if (align.equals("left") || align.equals("center") || align.equals("right") || align.equals("justify")) {
          sb.append(" align=\"").append(align).append('"');
        }
      }
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
      if (target != null && HtmlPolicy.lower(decode(target).trim()).equals("_blank")) {
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
    // AngularJS interpolates attribute values too: '{' is percent-encoded, which a browser does in
    // a path anyway, so the URL never holds "{{" (#248)
    StringBuilder sb = new StringBuilder(url.length() + 8);
    Escapers.escapeHtml(sb, url.replace("{", "%7B"), false);
    return sb.toString();
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
   * Writes a text attribute the policy allows. Known references are decoded and re-escaped;
   * well-formed unknown named references are kept (in a quoted value they only produce
   * characters). A value holding {@code {{} once decoded is dropped: AngularJS interpolates
   * attribute values, and they have no lossless break.
   */
  private void textAttribute(StringBuilder sb, String name, Map<String, String> attrs) {
    String raw = policy.attributes.contains(name) ? attrs.get(name) : null;
    if (raw == null || decode(raw).contains("{{")) {
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
      } else if (c == '&' && (end = unknownNamed(raw, i, raw.length())) > 0) {
        Escapers.escapeHtml(sb, pending.toString(), false);
        pending.setLength(0);
        sb.append(raw, i, end);
        i = end;
      } else {
        pending.append(c);
        i++;
      }
    }
    Escapers.escapeHtml(sb, pending.toString(), false);
    sb.append('"');
  }

  /** Writes a numeric attribute the policy allows: up to {@code digits} ASCII digits, optionally negative. */
  private void numberAttribute(StringBuilder sb, String name, Map<String, String> attrs, int digits, boolean signed) {
    String raw = policy.attributes.contains(name) ? attrs.get(name) : null;
    if (raw == null) {
      return;
    }
    String v = decode(raw).trim();
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
    while (pos < n && !isSpace(s.charAt(pos)) && s.charAt(pos) != '/' && s.charAt(pos) != '>') {
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
      String name = HtmlPolicy.lower(s.substring(start, pos));
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
   * can only ever produce a character. The references for braces are known, so every brace goes
   * through the text escaper, which breaks {@code {{} as {@link Escapers#text} does (#248).
   */
  private void text(int from, int to) {
    if (dropping != null || from >= to || (!open.isEmpty() && NO_TEXT.contains(open.get(open.size() - 1)))) {
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
      } else if ((end = unknownNamed(s, i, to)) > 0) {
        Escapers.escapeHtml(out, pending.toString(), true);
        pending.setLength(0);
        out.append(s, i, end);
        i = end;
      } else {
        pending.append('&');
        i++;
      }
    }
    Escapers.escapeHtml(out, pending.toString(), true);
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

  private static Set<String> set(String... names) {
    return new HashSet<>(Arrays.asList(names));
  }
}
