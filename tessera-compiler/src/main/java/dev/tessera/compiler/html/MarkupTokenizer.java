package dev.tessera.compiler.html;

/**
 * Splits a template into markup events. It accepts a positive grammar, the token rules of the
 * Tessera HTML profile (docs/context-model.md §2), and throws a {@link MarkupException} at the
 * first char outside it. Element names, balancing, duplicates and the rules that need the open
 * elements are {@link HtmlParser}'s job.
 *
 * <p>The grammar, where {@code ws} is ASCII whitespace (TAB, LF, FF, CR, SPACE) only:
 *
 * <ul>
 *   <li><b>Start tag</b>: {@code <} name, then attributes, then optional {@code ws} and {@code
 *       >} or {@code />}. A name is {@code [A-Za-z][A-Za-z0-9_.:-]*}. An attribute is a name
 *       (any chars but {@code ws / > = " ' <}) optionally followed by {@code =} and a value:
 *       double-quoted, single-quoted, or unquoted from {@code [A-Za-z0-9_.:/#-]}, not ending in
 *       {@code /} right before {@code >}. {@code ws} may surround the {@code =}. Attributes are
 *       separated by {@code ws}, or follow a quoted value directly. A {@code /} is accepted only
 *       right before the closing {@code >}.
 *   <li><b>End tag</b>: {@code </} name, optional {@code ws}, {@code >}.
 *   <li><b>Comment</b>: {@code <!--} to the first {@code -->} at least 7 chars in ({@link
 *       HtmlParser} rejects the comments a browser ends earlier).
 *   <li><b>CDATA</b>: {@code <![CDATA[} to the first {@code ]]>} ({@link HtmlParser} accepts it
 *       only in svg and math).
 *   <li><b>DOCTYPE</b>: {@code <!DOCTYPE html>} in any ASCII case, with {@code ws} between and
 *       after the words, before anything but whitespace and comments.
 *   <li><b>Raw text</b>: the content of an element for which {@link Handler#rawText} is true
 *       ends at the first {@code </name} (ASCII case-insensitive) followed by {@code ws}, {@code
 *       /} or {@code >}, where a browser ends it, or at the end of the template. {@link HtmlParser}
 *       picks {@code script}, {@code style}, {@code textarea} and {@code title} by name, also in
 *       svg and math, where a browser parses markup in them (#301 rejects markup there).
 *   <li><b>Text</b>: everything else. A {@code <} that starts none of the above is text here;
 *       {@link HtmlParser} rejects it.
 *   <li>U+0000 is accepted nowhere: a browser replaces it, so the source is not what it parses.
 * </ul>
 *
 * <p>Every attribute event carries the spans the grammar matched, so the attributes Tessera
 * reads, and rebuilds tags from, are the ones a browser reads.
 */
final class MarkupTokenizer {

  /** Receives the tokens; every range is [start, end) in the buffer. */
  interface Handler {
    void text(int start, int end);

    void comment(int start, int end);

    void cdata(int start, int end);

    /** The {@code <!DOCTYPE html>}. */
    void doctype(int start, int end);

    /** A start tag at {@code lt} whose name ends at {@code nameEnd}. */
    void startTag(int lt, int nameEnd);

    /** An attribute; {@code valueStart} is -1 when it has no value, {@code quote} 0 when unquoted. */
    void attribute(int nameStart, int nameEnd, int valueStart, int valueEnd, char quote);

    /** The start tag ends at {@code gt} and its element is open. */
    void openTagEnd(int gt);

    /** The start tag ends its element: {@code minimized} if written {@code />}, else a void element. */
    void selfClosedTagEnd(boolean minimized);

    /** An end tag at {@code lt} whose name ends at {@code nameEnd}. */
    void endTag(int lt, int nameEnd);

    /** Whether the element just opened ({@link #openTagEnd}) holds raw text that a browser does not tokenize. */
    boolean rawText();
  }

  /** A fatal tokenizing error at {@code offset}. */
  static final class MarkupException extends Exception {
    private static final long serialVersionUID = 1L;
    final int offset;

    MarkupException(String message, int offset) {
      super(message);
      this.offset = offset;
    }
  }

  private static final String[] VOID_ELEMENTS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "menuitem", "meta",
    "param", "source", "track", "wbr"
  };
  private static final String DOCTYPE_FORM =
      "only <!DOCTYPE html> is supported (other DOCTYPEs change how a browser parses the page): write <!DOCTYPE html>";

  private final char[] buf;
  private final int n;
  private final Handler handler;
  /** Whether anything but whitespace and comments came before; a DOCTYPE must not follow it. */
  private boolean content;

  MarkupTokenizer(char[] buf, Handler handler) {
    this.buf = buf;
    this.n = buf.length;
    this.handler = handler;
  }

  void run() throws MarkupException {
    for (int i = 0; i < n; i++) {
      if (buf[i] == 0) {
        throw new MarkupException("U+0000 (NUL) is not supported: a browser replaces it; remove it", i);
      }
    }
    int text = 0;
    int i = 0;
    while (i < n) {
      if (buf[i] != '<') {
        // a byte order mark is not content: a browser drops it when it decodes the page
        content |= !isSpace(buf[i]) && !(i == 0 && buf[i] == (char) 0xFEFF);
        i++;
        continue;
      }
      int end = markup(i, text);
      if (end < 0) {
        content = true;
        i++;
      } else {
        text = end;
        i = end;
      }
    }
    text(text, n);
  }

  /**
   * Reads the construct the {@code <} at {@code lt} starts, after the text from {@code text};
   * returns the offset after it, or -1 if it starts none (the {@code <} is text).
   */
  private int markup(int lt, int text) throws MarkupException {
    if (lt + 1 < n && isLetter(buf[lt + 1])) {
      text(text, lt);
      content = true;
      return startTag(lt);
    }
    if (lt + 2 < n && buf[lt + 1] == '/' && isLetter(buf[lt + 2])) {
      text(text, lt);
      content = true;
      return endTag(lt);
    }
    if (startsWith(lt, "<!--")) {
      text(text, lt);
      int end = delimitedEnd(lt, "<!---->", "--");
      handler.comment(lt, end);
      return end;
    }
    if (startsWith(lt, "<![CDATA[")) {
      text(text, lt);
      content = true;
      int end = delimitedEnd(lt, "<![CDATA[]]>", "]]");
      handler.cdata(lt, end);
      return end;
    }
    if (startsWith(lt, "<!") && asciiMatchesIgnoreCase(lt + 2, "DOCTYPE")) {
      text(text, lt);
      int end = doctype(lt);
      handler.doctype(lt, end);
      content = true;
      return end;
    }
    return -1;
  }

  private void text(int start, int end) {
    if (end > start) {
      handler.text(start, end);
    }
  }

  /** Reads the start tag at {@code lt}, and the raw text after it; returns the offset after them. */
  private int startTag(int lt) throws MarkupException {
    int nameEnd = name(lt + 1);
    handler.startTag(lt, nameEnd);
    int i = nameEnd;
    while (true) {
      while (i < n && isSpace(buf[i])) {
        i++;
      }
      if (i >= n) {
        throw incomplete(lt);
      }
      char c = buf[i];
      if (c == '>' || c == '/') {
        boolean minimized = c == '/';
        if (minimized) {
          i = slash(i);
        }
        String name = str(lt + 1, nameEnd);
        if (minimized || isVoid(name)) {
          handler.selfClosedTagEnd(minimized);
          return i + 1;
        }
        handler.openTagEnd(i);
        return handler.rawText() ? rawTextEnd(name, i + 1) : i + 1;
      }
      // after the name and after an unquoted value or a name there is whitespace here: they end
      // only at whitespace, '/', '>' (and a name at '='); after a quoted value there may be none
      i = attribute(i);
    }
  }

  /** Reads the attribute at {@code i}; returns the offset after it. */
  private int attribute(int i) throws MarkupException {
    int nameStart = i;
    if (buf[i] == '=') {
      throw new MarkupException("'=' with no attribute name before it: remove it or add the name", i);
    }
    while (i < n && !isSpace(buf[i]) && buf[i] != '/' && buf[i] != '>' && buf[i] != '=') {
      char c = buf[i];
      if (c == '"' || c == '\'') {
        throw new MarkupException("quote " + c + " in a tag or attribute name: remove it (names cannot contain quotes)", i);
      }
      if (c == '<') {
        throw new MarkupException("'<' in an attribute name: end the tag with '>' first, or remove the '<'", i);
      }
      notJavaOnlyWhitespace(i);
      i++;
    }
    int nameEnd = i;
    while (i < n && isSpace(buf[i])) {
      i++;
    }
    if (i >= n || buf[i] != '=') {
      handler.attribute(nameStart, nameEnd, -1, -1, (char) 0);
      return nameEnd;
    }
    int eq = i++;
    while (i < n && isSpace(buf[i])) {
      i++;
    }
    if (i >= n) {
      throw incomplete(nameStart);
    }
    char q = buf[i];
    if (q == '"' || q == '\'') {
      int close = indexOf(q, i + 1);
      if (close < 0) {
        throw incomplete(nameStart);
      }
      handler.attribute(nameStart, nameEnd, i + 1, close, q);
      return close + 1;
    }
    if (q == '=') {
      throw new MarkupException("'=' starting an unquoted value is part of it in a browser: quote the value", i);
    }
    if (q == '>') {
      throw new MarkupException("'=' with no value after it: write " + str(nameStart, nameEnd)
          + "=\"\" or remove the '='", eq);
    }
    int valueStart = i;
    while (i < n && !isSpace(buf[i]) && buf[i] != '>') {
      char c = buf[i];
      if (c == '"' || c == '\'') {
        throw new MarkupException("quote " + c + " in an unquoted attribute value: quote the value and write "
            + (c == '"' ? "&quot;" : "&#39;") + " in it", i);
      }
      notJavaOnlyWhitespace(i);
      if (!isUnquotedValueChar(c)) {
        throw new MarkupException("'" + c + "' in an unquoted attribute value: quote the value (an unquoted value"
            + " takes only ASCII letters, digits and _ . : / # -)", i);
      }
      i++;
    }
    if (i < n && buf[i] == '>' && buf[i - 1] == '/') {
      throw new MarkupException(
          "'/' before '>' belongs to the unquoted value, not to '/>': quote the value", i - 1);
    }
    handler.attribute(nameStart, nameEnd, valueStart, i, (char) 0);
    return i;
  }

  /** Checks that the {@code /} at {@code i} is right before {@code >}; returns the offset of the {@code >}. */
  private int slash(int i) throws MarkupException {
    if (i + 1 >= n) {
      throw incomplete(i);
    }
    char c = buf[i + 1];
    if (c == '>') {
      return i + 1;
    }
    if (c == '"' || c == '\'') {
      throw new MarkupException("quote " + c + " in a tag or attribute name: remove it (names cannot contain quotes)", i + 1);
    }
    throw new MarkupException(isSpace(c) || c == '/'
        ? "'/' in a tag must be followed by '>': remove the '/' or what follows it"
        : "a name after '/' in a tag starts a new attribute in a browser: remove the '/'", i + 1);
  }

  /** Reads the end tag at {@code lt}; returns the offset after it. */
  private int endTag(int lt) throws MarkupException {
    int nameEnd = name(lt + 2);
    int i = nameEnd;
    while (i < n && isSpace(buf[i])) {
      i++;
    }
    if (i >= n) {
      throw incomplete(lt);
    }
    if (buf[i] != '>') {
      notJavaOnlyWhitespace(i);
      String name = str(lt + 2, nameEnd);
      throw new MarkupException("end tag </" + name + "> has more than its name: write </" + name + ">", i);
    }
    handler.endTag(lt, nameEnd);
    return i + 1;
  }

  /** The end of the element name at {@code from}, which starts with a letter. */
  private int name(int from) throws MarkupException {
    int i = from;
    while (i < n && isNameChar(buf[i])) {
      i++;
    }
    if (i < n && !isSpace(buf[i]) && buf[i] != '/' && buf[i] != '>') {
      char c = buf[i];
      if (c == '"' || c == '\'') {
        throw new MarkupException("quote " + c + " in a tag or attribute name: remove it (names cannot contain quotes)", i);
      }
      notJavaOnlyWhitespace(i);
      throw new MarkupException("'" + c + "' in an element name: element names start with a letter and use letters,"
          + " digits, '-', '_', ':' and '.'; write &lt; for a literal '<'", i);
    }
    return i;
  }

  /** Rejects a char at {@code i} that {@link Character#isWhitespace} accepts but HTML does not. */
  private void notJavaOnlyWhitespace(int i) throws MarkupException {
    if (Character.isWhitespace(buf[i])) {
      throw new MarkupException("U+" + String.format("%04X", (int) buf[i])
          + " in a tag is not HTML whitespace: replace it with a space", i);
    }
  }

  /** Emits the raw text from {@code from} to the first {@code </name} a browser ends it at; returns that offset. */
  private int rawTextEnd(String name, int from) {
    for (int i = from; i + name.length() + 2 <= n; i++) {
      if (buf[i] == '<' && buf[i + 1] == '/' && asciiMatchesIgnoreCase(i + 2, name)) {
        int next = i + 2 + name.length();
        if (next == n || isSpace(buf[next]) || buf[next] == '/' || buf[next] == '>') {
          text(from, i);
          return i;
        }
      }
    }
    text(from, n);
    return n;
  }

  /** Reads the {@code <!DOCTYPE html>} at {@code lt}; returns the offset after it. */
  private int doctype(int lt) throws MarkupException {
    if (content) {
      throw new MarkupException("<!DOCTYPE> after other content is ignored by a browser: move it to the top of the"
          + " template, or remove it", lt);
    }
    int i = lt + 9;
    int words = i;
    while (i < n && isSpace(buf[i])) {
      i++;
    }
    if (i == words || !asciiMatchesIgnoreCase(i, "HTML")) {
      throw new MarkupException(DOCTYPE_FORM, lt);
    }
    i += 4;
    while (i < n && isSpace(buf[i])) {
      i++;
    }
    if (i >= n || buf[i] != '>') {
      throw new MarkupException(DOCTYPE_FORM, lt);
    }
    return i + 1;
  }

  private MarkupException incomplete(int start) {
    int end = start;
    while (end < n && end - start < 40 && buf[end] != '\n') {
      end++;
    }
    return new MarkupException("\"" + str(start, end) + (end < n ? "..." : "") + "\" does not end before the end of"
        + " the template: close the tag, quote, comment or CDATA section", start);
  }

  /**
   * The offset after the first {@code >} right after {@code before} that ends a structure at
   * least as long as {@code shortest}.
   */
  private int delimitedEnd(int start, String shortest, String before) throws MarkupException {
    for (int i = start + shortest.length() - 1; i < n; i++) {
      if (buf[i] == '>' && startsWith(i - before.length(), before)) {
        return i + 1;
      }
    }
    throw incomplete(start);
  }

  static boolean isVoid(String name) {
    for (String v : VOID_ELEMENTS) {
      if (v.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  private static boolean isLetter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private static boolean isNameChar(char c) {
    return isLetter(c) || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == ':';
  }

  private static boolean isUnquotedValueChar(char c) {
    return isNameChar(c) || c == '/' || c == '#';
  }

  private int indexOf(char c, int from) {
    for (int i = from; i < n; i++) {
      if (buf[i] == c) {
        return i;
      }
    }
    return -1;
  }

  private boolean startsWith(int i, String s) {
    if (i < 0 || n - i < s.length()) {
      return false;
    }
    for (int j = 0; j < s.length(); j++) {
      if (buf[i + j] != s.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code s} is at {@code i}, folding ASCII letters only, as browsers do. */
  private boolean asciiMatchesIgnoreCase(int i, String s) {
    if (n - i < s.length()) {
      return false;
    }
    for (int j = 0; j < s.length(); j++) {
      char a = buf[i + j];
      char b = s.charAt(j);
      if (a != b && asciiLower(a) != asciiLower(b)) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  private String str(int start, int end) {
    return new String(buf, start, end - start);
  }
}
