package dev.tessera.compiler.html;

/**
 * Splits a template into markup events. It tokenizes exactly as attoparser 2.0.9 did in HTML mode
 * with the configuration Tessera used (no balancing and no validation beyond structure), so
 * templates parse the same as they did before Tessera had its own tokenizer. Balancing,
 * names, duplicates and raw-text handling are {@link HtmlParser}'s job.
 *
 * <p>Rules, all inherited from attoparser:
 *
 * <ul>
 *   <li>A {@code <} starts, in this order of preference: a start tag ({@code <} and a name char,
 *       anything but {@code - ! / ? [} or whitespace, except {@code <!DOCTYPE}), an end tag, a
 *       comment, a CDATA section, a DOCTYPE or a processing instruction. Any other {@code <} is
 *       text.
 *   <li>Tags and DOCTYPEs end at the first {@code >} outside quotes; both quote kinds toggle
 *       anywhere in the tag. A tag ending in {@code />} is self-closing. Names end at HTML
 *       whitespace outside quotes.
 *   <li>An attribute name ends at {@code =} or whitespace; it has a value if the following run of
 *       {@code =} and whitespace has an {@code =}. A quoted value ends at its closing quote, an
 *       unquoted one at whitespace.
 *   <li>Void elements are always self-closed. The content of {@code <style>}, and of {@code
 *       <script>} without a {@code type} or with a JavaScript one, is not tokenized: it ends at the
 *       first {@code </name>} spelled as in the start tag.
 *   <li>A structure without its end is fatal, as are a few malformed tags; the {@link
 *       MarkupException} messages are attoparser's. Malformed DOCTYPEs and processing instructions
 *       (an XML declaration is one) are the verifier's parse errors.
 * </ul>
 *
 * <p>Some rules are Tessera's own: in a start or end tag, a quote that a browser does not read as
 * the start or end of a quoted attribute value (one in a name, in an unquoted value or right after
 * a quoted value) is fatal, because with it the tag could end at a different {@code >} than in a
 * browser. So are a char outside quoted values that Java or Unicode counts as a space but HTML does
 * not (a browser keeps it in the name or value), a {@code /} that ends an unquoted value before
 * {@code >}, an {@code =} that starts an unquoted value, and anything but {@code >} after a
 * {@code /} outside a value, because a browser splits or ends the tag differently there. Raw text
 * that a browser does not tokenize ({@link Handler#rawText}) is exempt.
 *
 * <p>Thymeleaf's parser-level comment {@code <!--/* ... *&#47;-->} is a template comment, also
 * Tessera's own: from {@code <!--/*} to the first {@code *&#47;-->} it is one {@link
 * Handler#templateComment} token, never output and never parsed. One without its end is
 * fatal. In raw text ({@link Handler#rawText}, and the untokenized content of {@code <script>} and
 * {@code <style>}) it is not recognized, as a browser reads it as text there; {@code HtmlParser}
 * rejects it in that content. Thymeleaf's prototype-only comment {@code <!--/*&#47; ... /*&#47;-->} starts
 * the same way and is read as one too, which {@code HtmlParser} rejects.
 *
 * <p>Offsets index the buffer, which the handler may change ahead of the tokenizer (see {@code
 * HtmlParser.Builder.matchEndTagCase}).
 */
final class MarkupTokenizer {

  /** Receives the tokens; every range is [start, end) in the buffer. */
  interface Handler {
    void text(int start, int end);

    void comment(int start, int end);

    /** A template comment {@code <!--/* ... *&#47;-->}, which is dropped. */
    void templateComment(int start, int end);

    void cdata(int start, int end);

    /** A DOCTYPE or a processing instruction (an XML declaration is one). */
    void declaration(int start, int end);

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

    /**
     * Whether the tokens are raw text a browser does not tokenize (the handler finds where it
     * ends); there, tags keep the toggling quote rule and a stray quote is no error.
     */
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

  private interface AttributeSink {
    void attribute(int nameStart, int nameEnd, int valueStart, int valueEnd, char quote) throws MarkupException;
  }

  private static final String[] VOID_ELEMENTS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "menuitem", "meta",
    "param", "source", "track", "wbr"
  };
  private static final String[] JAVASCRIPT_TYPES = {
    "javascript", "ecmascript", "text/javascript", "text/ecmascript", "application/javascript",
    "application/ecmascript", "module"
  };

  private static final int NONE = 0;
  private static final int START_TAG = 1;
  private static final int END_TAG = 2;
  private static final int COMMENT = 3;
  private static final int CDATA = 4;
  private static final int DOCTYPE = 5;
  private static final int PROCESSING_INSTRUCTION = 6;
  private static final int TEMPLATE_COMMENT = 7;

  private static final char[] TEMPLATE_COMMENT_END = "*/-->".toCharArray();

  private final char[] buf;
  private final int n;
  private final Handler handler;

  /** While in untokenized content: the {@code </name>} that ends it. */
  private char[] cdataEnd;

  MarkupTokenizer(char[] buf, Handler handler) {
    this.buf = buf;
    this.n = buf.length;
    this.handler = handler;
  }

  void run() throws MarkupException {
    int current = 0;
    while (current < n) {
      if (cdataEnd != null) {
        int end = indexOf(cdataEnd, current);
        if (end < 0) {
          break;
        }
        text(current, end);
        cdataEnd = null;
        current = end;
      }
      int start = indexOf('<', current, n);
      while (start >= 0 && kind(start) == NONE) {
        start = indexOf('<', start + 1, n);
      }
      if (start < 0) {
        break;
      }
      text(current, start);
      current = structure(kind(start), start) + 1;
    }
    text(current, n);
  }

  private void text(int start, int end) {
    if (end > start) {
      handler.text(start, end);
    }
  }

  private int kind(int i) {
    if (isElementName(i + 1, n)) {
      return START_TAG;
    }
    if (n - i > 2 && buf[i + 1] == '/' && isElementName(i + 2, n)) {
      return END_TAG;
    }
    if (startsWith(i, "<!--/*") && !handler.rawText()) {
      return TEMPLATE_COMMENT;
    }
    if (startsWith(i, "<!--")) {
      return COMMENT;
    }
    if (n - i > 8 && startsWith(i, "<![") && keyword(i + 3, "CDATA") && buf[i + 8] == '[') {
      return CDATA;
    }
    if (n - i > 9 && startsWith(i, "<!") && keyword(i + 2, "DOCTYPE")
        && (HtmlParser.isHtmlWhitespace(buf[i + 9]) || buf[i + 9] == '>')) {
      return DOCTYPE;
    }
    if (n - i > 2 && buf[i + 1] == '?' && !HtmlParser.isHtmlWhitespace(buf[i + 2])) {
      return PROCESSING_INSTRUCTION;
    }
    return NONE;
  }

  /**
   * Whether an element name starts at {@code i}, looking no further than {@code to} (attoparser's
   * rule, including its {@code <!x} names; the answer can depend on {@code to}).
   */
  private boolean isElementName(int i, int to) {
    int len = to - i;
    if (len > 1 && buf[i] == '!') {
      char c = buf[i + 1];
      if (c == '-' || c == '!' || c == '/' || c == '?' || c == '[') {
        return false;
      }
      if (len > 8) {
        return !(keyword(i + 1, "DOCTYPE") && (HtmlParser.isHtmlWhitespace(buf[i + 8]) || buf[i + 8] == '>'));
      }
      return !HtmlParser.isHtmlWhitespace(c);
    }
    if (len <= 0) {
      return false;
    }
    char c = buf[i];
    return c != '-' && c != '!' && c != '/' && c != '?' && c != '[' && !HtmlParser.isHtmlWhitespace(c);
  }

  /** Reads the structure of {@code kind} at {@code start}; returns the offset of its last char. */
  private int structure(int kind, int start) throws MarkupException {
    switch (kind) {
      case START_TAG: {
        int end = handler.rawText() ? tagEnd(start) : tagEnd(start, start + 1);
        boolean minimized = buf[end - 1] == '/';
        if (end - start + 1 < (minimized ? 4 : 3) || !isElementName(start + 1, end + 1)) {
          throw new MarkupException("Could not parse as a well-formed " + (minimized ? "standalone" : "open")
              + " element: \"" + str(start, end + 1) + "\"", start);
        }
        startTag(start, minimized ? end - 1 : end, minimized);
        return end;
      }
      case END_TAG: {
        int end = handler.rawText() ? tagEnd(start) : tagEnd(start, start + 2);
        endTag(start, end);
        return end;
      }
      case COMMENT: {
        int end = delimitedEnd(start, "<!---->", "--");
        handler.comment(start, end + 1);
        return end;
      }
      case TEMPLATE_COMMENT: {
        int end = indexOf(TEMPLATE_COMMENT_END, start + 6);
        if (end < 0) {
          throw new MarkupException("'<!--/*' starts a template comment that is not closed: end it with '*/-->'",
              start);
        }
        handler.templateComment(start, end + TEMPLATE_COMMENT_END.length);
        return end + TEMPLATE_COMMENT_END.length - 1;
      }
      case CDATA: {
        int end = delimitedEnd(start, "<![CDATA[]]>", "]]");
        handler.cdata(start, end + 1);
        return end;
      }
      case PROCESSING_INSTRUCTION: {
        int end = delimitedEnd(start, "<?xy?>", "?");
        handler.declaration(start, end + 1);
        return end;
      }
      case DOCTYPE: {
        int end = docTypeEnd(start);
        handler.declaration(start, end + 1);
        return end;
      }
      default:
        throw new IllegalStateException("structure " + kind);
    }
  }

  private MarkupException incomplete(int start) {
    return new MarkupException("Incomplete structure: \"" + str(start, n) + "\"", start);
  }

  // The WHATWG tokenizer states inside a tag, as far as tagEnd(start, from) tracks them.
  private static final int TAG_NAME = 0;
  private static final int BEFORE_ATTRIBUTE_NAME = 1;
  private static final int ATTRIBUTE_NAME = 2;
  private static final int AFTER_ATTRIBUTE_NAME = 3;
  private static final int BEFORE_ATTRIBUTE_VALUE = 4;
  private static final int DOUBLE_QUOTED_VALUE = 5;
  private static final int SINGLE_QUOTED_VALUE = 6;
  private static final int UNQUOTED_VALUE = 7;
  private static final int AFTER_QUOTED_VALUE = 8;
  private static final int SELF_CLOSING = 9;

  /**
   * The {@code >} that ends the start or end tag at {@code start}, whose name starts at {@code
   * from}. A browser opens a quoted attribute value only at a quote right after an attribute's
   * {@code =} (and optional whitespace); any other quote is a plain character of a name or an
   * unquoted value. The other tokenizer rules toggle on every quote, so such a quote is an error:
   * with it the tag could end at a different {@code >} than in a browser. Without one, both end at
   * the first {@code >} outside a quoted value.
   *
   * <p>Four more differences between the tokenizer and a browser are errors here for the same
   * reason: a char that Java or Unicode counts as a space but HTML does not (HTML's are {@code \t
   * \n \f \r} and space), which a browser keeps in the name or value; a {@code /} that ends an
   * unquoted value right before {@code >}, which a browser keeps in the value (the tag is not
   * {@code />}); an {@code =} that starts an unquoted value, which a browser keeps in the value
   * where the tokenizer skips it; and anything but {@code >} after a {@code /} outside a value: a
   * browser starts a new attribute at a name there, and the tokenizer reads whitespace or another
   * {@code /} after it as an attribute named {@code /}.
   */
  private int tagEnd(int start, int from) throws MarkupException {
    int state = TAG_NAME;
    for (int i = from; i < n; i++) {
      char c = buf[i];
      boolean space = HtmlParser.isHtmlWhitespace(c);
      if (state == DOUBLE_QUOTED_VALUE || state == SINGLE_QUOTED_VALUE) {
        if (c == (state == DOUBLE_QUOTED_VALUE ? '"' : '\'')) {
          state = AFTER_QUOTED_VALUE;
        }
        continue;
      }
      if (state == BEFORE_ATTRIBUTE_VALUE && (c == '"' || c == '\'')) {
        state = c == '"' ? DOUBLE_QUOTED_VALUE : SINGLE_QUOTED_VALUE;
        continue;
      }
      if (c == '"' || c == '\'') {
        // in every other state a browser reads the quote as part of a tag or attribute name
        throw new MarkupException(state == UNQUOTED_VALUE
            ? "quote " + c + " in an unquoted attribute value: quote the value and write "
                + (c == '"' ? "&quot;" : "&#39;") + " in it"
            : "quote " + c + " in a tag or attribute name: remove it (names cannot contain quotes)", i);
      }
      if (!space && isNonHtmlSpace(c)) {
        throw new MarkupException(String.format(
            "U+%04X in a tag is not HTML whitespace: replace it with a space", (int) c), i);
      }
      if (c == '>') {
        if (state == UNQUOTED_VALUE && buf[i - 1] == '/') {
          throw new MarkupException(
              "'/' before '>' belongs to the unquoted value, not to '/>': quote the value", i - 1);
        }
        return i;
      }
      if (state == SELF_CLOSING) {
        throw new MarkupException(space || c == '/'
            ? "'/' in a tag must be followed by '>': remove the '/' or what follows it"
            : "a name after '/' in a tag starts a new attribute in a browser: remove the '/'", i);
      }
      if (state == BEFORE_ATTRIBUTE_VALUE && c == '=') {
        throw new MarkupException(
            "'=' starting an unquoted value is part of it in a browser: quote the value", i);
      }
      switch (state) {
        case TAG_NAME:
        case UNQUOTED_VALUE:
        case AFTER_QUOTED_VALUE:
          if (space) {
            state = BEFORE_ATTRIBUTE_NAME;
          } else if (c == '/' && state != UNQUOTED_VALUE) {
            state = SELF_CLOSING;
          } else if (state == AFTER_QUOTED_VALUE) {
            state = ATTRIBUTE_NAME;
          }
          break;
        case BEFORE_ATTRIBUTE_NAME:
          if (c == '/') {
            state = SELF_CLOSING;
          } else if (!space) {
            state = ATTRIBUTE_NAME; // a leading = is part of the name
          } else {
            state = BEFORE_ATTRIBUTE_NAME;
          }
          break;
        case ATTRIBUTE_NAME:
        case AFTER_ATTRIBUTE_NAME:
          if (c == '=') {
            state = BEFORE_ATTRIBUTE_VALUE;
          } else if (c == '/') {
            state = SELF_CLOSING;
          } else if (space) {
            state = AFTER_ATTRIBUTE_NAME;
          } else {
            state = ATTRIBUTE_NAME;
          }
          break;
        case BEFORE_ATTRIBUTE_VALUE:
          if (!space) {
            state = UNQUOTED_VALUE;
          }
          break;
        default:
          throw new IllegalStateException("state " + state);
      }
    }
    throw incomplete(start);
  }

  /** The first {@code >} outside quotes after {@code start}, both quote kinds toggling anywhere. */
  private int tagEnd(int start) throws MarkupException {
    boolean inQuotes = false;
    boolean inApos = false;
    for (int i = start; i < n; i++) {
      char c = buf[i];
      if (c == '"' && !inApos) {
        inQuotes = !inQuotes;
      } else if (c == '\'' && !inQuotes) {
        inApos = !inApos;
      } else if (c == '>' && !inQuotes && !inApos) {
        return i;
      }
    }
    throw incomplete(start);
  }

  /**
   * The first {@code >} right after {@code before} that ends a structure at least as long as {@code
   * shortest}.
   */
  private int delimitedEnd(int start, String shortest, String before) throws MarkupException {
    for (int i = start + shortest.length() - 1; i < n; i++) {
      if (buf[i] == '>' && startsWith(i - before.length(), before)) {
        return i;
      }
    }
    throw incomplete(start);
  }

  /** The first {@code >} outside quotes and {@code [...]} after {@code start}. */
  private int docTypeEnd(int start) throws MarkupException {
    boolean inQuotes = false;
    boolean inApos = false;
    int brackets = 0;
    for (int i = start; i < n; i++) {
      char c = buf[i];
      if (c == '"' && !inApos) {
        inQuotes = !inQuotes;
      } else if (c == '\'' && !inQuotes) {
        inApos = !inApos;
      } else if (!inQuotes && !inApos) {
        if (c == '[') {
          brackets++;
        } else if (c == ']') {
          brackets--;
        } else if (c == '>' && brackets == 0) {
          return i;
        }
      }
    }
    throw incomplete(start);
  }

  /** A start tag from {@code start} ({@code <}) to {@code end} ({@code >} or the {@code /} of {@code />}). */
  private void startTag(int start, int end, boolean minimized) throws MarkupException {
    int nameEnd = whitespace(start + 1, end, true);
    if (nameEnd < 0) {
      nameEnd = end;
    }
    String name = str(start + 1, nameEnd);
    boolean isVoid = !minimized && isVoid(name);
    boolean script = HtmlParser.asciiEqualsIgnoreCase(name, "script");
    boolean[] raw = {!minimized && !isVoid && (script || HtmlParser.asciiEqualsIgnoreCase(name, "style"))};
    handler.startTag(start, nameEnd);
    attributes(nameEnd, end, (ns, ne, vs, ve, quote) -> {
      if (script && HtmlParser.asciiEqualsIgnoreCase(str(ns, ne), "type")) {
        raw[0] = vs >= 0 && isJavaScriptType(str(vs, ve));
      }
      handler.attribute(ns, ne, vs, ve, quote);
    });
    if (minimized || isVoid) {
      handler.selfClosedTagEnd(minimized);
      return;
    }
    handler.openTagEnd(end);
    if (raw[0]) {
      cdataEnd = ("</" + name + ">").toCharArray();
    }
  }

  private static boolean isVoid(String name) {
    for (String v : VOID_ELEMENTS) {
      if (HtmlParser.asciiEqualsIgnoreCase(v, name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isJavaScriptType(String type) {
    for (String t : JAVASCRIPT_TYPES) {
      if (HtmlParser.asciiEqualsIgnoreCase(t, type)) {
        return true;
      }
    }
    return false;
  }

  /** An end tag from {@code start} ({@code <}) to {@code end} ({@code >}). */
  private void endTag(int start, int end) throws MarkupException {
    if (end - start + 1 < 4 || !isElementName(start + 2, end + 1)) {
      throw new MarkupException("Could not parse as a well-formed close element: \"" + str(start, end + 1) + "\"", start);
    }
    int nameEnd = whitespace(start + 2, end, true);
    if (nameEnd < 0) {
      nameEnd = end;
    }
    handler.endTag(start, nameEnd);
    if (nonWhitespace(nameEnd, end) >= 0) {
      throw new MarkupException("Could not parse as a well-formed closing element \"</" + str(start + 2, end)
          + ">\": No attributes are allowed here", start);
    }
  }

  /** Reads the attributes in [{@code from}, {@code to}). */
  private void attributes(int from, int to, AttributeSink sink) throws MarkupException {
    int i = from;
    while (i < to) {
      i = nonWhitespace(i, to);
      if (i < 0) {
        return;
      }
      int nameStart = i;
      int nameEnd = operator(i, to);
      if (nameEnd < 0) {
        sink.attribute(nameStart, to, -1, -1, (char) 0);
        return;
      }
      if (nameEnd == nameStart) {
        throw new MarkupException("Bad attribute name in sequence \"" + str(from, to)
            + "\": attribute names cannot start with an equals sign", nameStart);
      }
      int operatorEnd = nonOperator(nameEnd, to);
      boolean equals = indexOf('=', nameEnd, operatorEnd < 0 ? to : operatorEnd) >= 0;
      if (operatorEnd < 0) {
        sink.attribute(nameStart, nameEnd, equals ? to : -1, equals ? to : -1, (char) 0);
        return;
      }
      if (!equals) {
        sink.attribute(nameStart, nameEnd, -1, -1, (char) 0);
        i = operatorEnd;
        continue;
      }
      int valueStart = operatorEnd;
      char first = buf[valueStart];
      int valueEnd;
      if (first == '"' || first == '\'') {
        int close = indexOf(first, valueStart + 1, to);
        valueEnd = close < 0 || close + 1 >= to ? -1 : close + 1;
      } else {
        valueEnd = whitespace(valueStart, to, false);
      }
      int outerEnd = valueEnd < 0 ? to : valueEnd;
      char last = buf[outerEnd - 1];
      if (outerEnd - valueStart >= 2 && (first == '"' || first == '\'') && last == first) {
        sink.attribute(nameStart, nameEnd, valueStart + 1, outerEnd - 1, first);
      } else {
        sink.attribute(nameStart, nameEnd, valueStart, outerEnd, (char) 0);
      }
      if (valueEnd < 0) {
        return;
      }
      i = valueEnd;
    }
  }

  // ---- scanning helpers; each returns -1 when it reaches the end of its range ----

  /** The first whitespace in [{@code from}, {@code to}), optionally skipping quoted text. */
  private int whitespace(int from, int to, boolean avoidQuotes) {
    boolean inQuotes = false;
    boolean inApos = false;
    for (int i = from; i < to; i++) {
      char c = buf[i];
      if (avoidQuotes && c == '"' && !inApos) {
        inQuotes = !inQuotes;
      } else if (avoidQuotes && c == '\'' && !inQuotes) {
        inApos = !inApos;
      } else if (!inQuotes && !inApos && HtmlParser.isHtmlWhitespace(c)) {
        return i;
      }
    }
    return -1;
  }

  private int nonWhitespace(int from, int to) {
    for (int i = from; i < to; i++) {
      if (!HtmlParser.isHtmlWhitespace(buf[i])) {
        return i;
      }
    }
    return -1;
  }

  /** The first {@code =} or whitespace. */
  private int operator(int from, int to) {
    for (int i = from; i < to; i++) {
      if (buf[i] == '=' || HtmlParser.isHtmlWhitespace(buf[i])) {
        return i;
      }
    }
    return -1;
  }

  /** The first char that is neither {@code =} nor whitespace. */
  private int nonOperator(int from, int to) {
    for (int i = from; i < to; i++) {
      if (buf[i] != '=' && !HtmlParser.isHtmlWhitespace(buf[i])) {
        return i;
      }
    }
    return -1;
  }

  private int indexOf(char c, int from, int to) {
    for (int i = from; i < to; i++) {
      if (buf[i] == c) {
        return i;
      }
    }
    return -1;
  }

  private int indexOf(char[] seq, int from) {
    for (int i = from; i + seq.length <= n; i++) {
      int j = 0;
      while (j < seq.length && buf[i + j] == seq[j]) {
        j++;
      }
      if (j == seq.length) {
        return i;
      }
    }
    return -1;
  }

  private boolean startsWith(int i, String s) {
    if (n - i < s.length()) {
      return false;
    }
    for (int j = 0; j < s.length(); j++) {
      if (buf[i + j] != s.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code upper} (ASCII upper case) is at {@code i}, in upper or lower case per char. */
  private boolean keyword(int i, String upper) {
    if (n - i < upper.length()) {
      return false;
    }
    for (int j = 0; j < upper.length(); j++) {
      char c = buf[i + j];
      char u = upper.charAt(j);
      if (c != u && c != HtmlParser.asciiLower(u)) {
        return false;
      }
    }
    return true;
  }

  /**
   * A char that Java or Unicode counts as a space but HTML does not (U+000B, U+001C to U+001F, the
   * Unicode space and line separators): in a tag it is part of a name or value, not a separator.
   */
  private static boolean isNonHtmlSpace(char c) {
    int type = Character.getType(c);
    return !HtmlParser.isHtmlWhitespace(c)
        && (c == 0x0B || (c >= 0x1C && c <= 0x1F)
            || (type == Character.SPACE_SEPARATOR && c != 0xA0 && c != 0x2007 && c != 0x202F)
            || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR);
  }

  private String str(int start, int end) {
    return new String(buf, start, end - start);
  }
}
