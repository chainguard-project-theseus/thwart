package dev.tessera.compiler.html;

/**
 * Splits a template into markup events. It tokenizes exactly as attoparser 2.0.9 did in HTML mode
 * with the configuration Tessera used (no balancing and no validation beyond structure), so
 * templates parse the same as they did before Tessera had its own tokenizer (#170). Balancing,
 * names, duplicates and raw-text handling are {@link HtmlParser}'s job.
 *
 * <p>Rules, all inherited from attoparser:
 *
 * <ul>
 *   <li>A {@code <} starts, in this order of preference: a start tag ({@code <} and a name char,
 *       anything but {@code - ! / ? [} or whitespace, except {@code <!DOCTYPE}), an end tag, a
 *       comment, a CDATA section, a DOCTYPE, an XML declaration or a processing instruction. Any
 *       other {@code <} is text.
 *   <li>Tags and declarations end at the first {@code >} outside quotes; both quote kinds toggle
 *       anywhere in the tag. A tag ending in {@code />} is self-closing. Names end at whitespace
 *       ({@link Character#isWhitespace}) outside quotes.
 *   <li>An attribute name ends at {@code =} or whitespace; it has a value if the following run of
 *       {@code =} and whitespace has an {@code =}. A quoted value ends at its closing quote, an
 *       unquoted one at whitespace.
 *   <li>Void elements are always self-closed. The content of {@code <style>}, and of {@code
 *       <script>} without a {@code type} or with a JavaScript one, is not tokenized: it ends at the
 *       first {@code </name>} spelled as in the start tag.
 *   <li>A structure without its end is fatal, as are a few malformed tags and declarations; the
 *       {@link MarkupException} messages are attoparser's.
 * </ul>
 *
 * <p>Offsets index the buffer, which the handler may change ahead of the tokenizer (see {@code
 * HtmlParser.Builder.matchEndTagCase}).
 */
final class MarkupTokenizer {

  /** Receives the tokens; every range is [start, end) in the buffer. */
  interface Handler {
    void text(int start, int end);

    void comment(int start, int end);

    void cdata(int start, int end);

    /** A DOCTYPE, an XML declaration or a processing instruction. */
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
  private static final int XML_DECLARATION = 6;
  private static final int PROCESSING_INSTRUCTION = 7;

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
    if (startsWith(i, "<!--")) {
      return COMMENT;
    }
    if (n - i > 8 && startsWith(i, "<![") && keyword(i + 3, "CDATA") && buf[i + 8] == '[') {
      return CDATA;
    }
    if (n - i > 9 && startsWith(i, "<!") && keyword(i + 2, "DOCTYPE")
        && (Character.isWhitespace(buf[i + 9]) || buf[i + 9] == '>')) {
      return DOCTYPE;
    }
    if (n - i > 5 && startsWith(i, "<?xml")
        && (Character.isWhitespace(buf[i + 5]) || (n - i > 6 && buf[i + 5] == '?' && buf[i + 6] == '>'))) {
      return XML_DECLARATION;
    }
    if (n - i > 2 && buf[i + 1] == '?' && !Character.isWhitespace(buf[i + 2])
        && !(n - i > 5 && startsWith(i + 2, "xml") && Character.isWhitespace(buf[i + 5]))) {
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
        return !(keyword(i + 1, "DOCTYPE") && (Character.isWhitespace(buf[i + 8]) || buf[i + 8] == '>'));
      }
      return !Character.isWhitespace(c);
    }
    if (len <= 0) {
      return false;
    }
    char c = buf[i];
    return c != '-' && c != '!' && c != '/' && c != '?' && c != '[' && !Character.isWhitespace(c);
  }

  /** Reads the structure of {@code kind} at {@code start}; returns the offset of its last char. */
  private int structure(int kind, int start) throws MarkupException {
    switch (kind) {
      case START_TAG: {
        int end = tagEnd(start);
        boolean minimized = buf[end - 1] == '/';
        if (end - start + 1 < (minimized ? 4 : 3) || !isElementName(start + 1, end + 1)) {
          throw new MarkupException("Could not parse as a well-formed " + (minimized ? "standalone" : "open")
              + " element: \"" + str(start, end + 1) + "\"", start);
        }
        startTag(start, minimized ? end - 1 : end, minimized);
        return end;
      }
      case END_TAG: {
        int end = tagEnd(start);
        endTag(start, end);
        return end;
      }
      case COMMENT: {
        int end = delimitedEnd(start, "<!---->", "--");
        handler.comment(start, end + 1);
        return end;
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
        checkDocType(start, end);
        handler.declaration(start, end + 1);
        return end;
      }
      case XML_DECLARATION: {
        int end = tagEnd(start);
        checkXmlDeclaration(start, end);
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

  /** The first {@code >} outside quotes after {@code start}. */
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
    boolean script = name.equalsIgnoreCase("script");
    boolean[] raw = {!minimized && !isVoid && (script || name.equalsIgnoreCase("style"))};
    handler.startTag(start, nameEnd);
    attributes(nameEnd, end, (ns, ne, vs, ve, quote) -> {
      if (script && str(ns, ne).equalsIgnoreCase("type")) {
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
      if (v.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isJavaScriptType(String type) {
    for (String t : JAVASCRIPT_TYPES) {
      if (t.equalsIgnoreCase(type)) {
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

  /** Checks a DOCTYPE from {@code start} to {@code end} ({@code >}) the way attoparser does. */
  private void checkDocType(int start, int end) throws MarkupException {
    String outer = str(start, end + 1);
    String clause = "Could not parse as a well-formed DOCTYPE clause \"" + outer + "\": ";
    String needId = clause + "If a type is specified (PUBLIC or SYSTEM), at least a public or a system ID has to be specified";
    String quotes = clause + "Public and System IDs must be surrounded by quotes (\")";
    String systemOne = clause + "type SYSTEM only allows specifying one element (a system ID)";
    int from = start + 2;
    int to = end;
    int last = to - 1;
    while (last > from && Character.isWhitespace(buf[last])) {
      last--;
    }
    if (last > from && buf[last] == ']') {
      to = internalSubsetStart(from, to);
      if (to < 0) {
        throw new MarkupException("Could not parse as a well-formed DOCTYPE clause: \"" + outer + "\"", start);
      }
    }
    // keyword, element name, type, then one or two quoted IDs
    int i = whitespace(from, to, false);
    i = i < 0 ? -1 : nonWhitespace(i, to);
    i = i < 0 ? -1 : whitespace(i, to, false);
    i = i < 0 ? -1 : nonWhitespace(i, to);
    if (i < 0) {
      return;
    }
    int typeEnd = whitespace(i, to, true);
    if (typeEnd < 0) {
      throw new MarkupException(needId, start);
    }
    if (!(typeEnd - i == 6 && (keyword(i, "PUBLIC") || keyword(i, "SYSTEM")))) {
      throw new MarkupException(clause + "DOCTYPE type must be either \"PUBLIC\" or \"SYSTEM\"", start);
    }
    boolean isPublic = buf[i] == 'P' || buf[i] == 'p';
    i = nonWhitespace(typeEnd, to);
    if (i < 0) {
      throw new MarkupException(needId, start);
    }
    for (int id = 1; ; id++) {
      int idEnd = whitespace(i, to, true);
      if (!isQuoted(i, idEnd < 0 ? to : idEnd)) {
        throw new MarkupException(quotes, start);
      }
      if (id == 2 && !isPublic) {
        throw new MarkupException(systemOne, start);
      }
      if (idEnd < 0) {
        return;
      }
      i = nonWhitespace(idEnd, to);
      if (i < 0) {
        return;
      }
      if (id == 2) {
        throw new MarkupException(clause + "More elements found than allowed", start);
      }
    }
  }

  private int internalSubsetStart(int from, int to) {
    boolean inQuotes = false;
    boolean inApos = false;
    for (int i = from; i < to; i++) {
      char c = buf[i];
      if (c == '"' && !inApos) {
        inQuotes = !inQuotes;
      } else if (c == '\'' && !inQuotes) {
        inApos = !inApos;
      } else if (c == '[' && !inQuotes && !inApos) {
        return i;
      }
    }
    return -1;
  }

  private boolean isQuoted(int start, int end) {
    return end - start >= 2 && (buf[start] == '"' || buf[start] == '\'') && buf[end - 1] == buf[start];
  }

  /** Checks an XML declaration from {@code start} to {@code end} ({@code >}) the way attoparser does. */
  private void checkXmlDeclaration(int start, int end) throws MarkupException {
    String outer = "\"" + str(start, end + 1) + "\"";
    if (end - start + 1 < 7 || buf[end - 1] != '?') {
      throw new MarkupException("Could not parse as a well-formed XML Declaration: " + outer, start);
    }
    int to = end - 1;
    int keywordEnd = whitespace(start + 2, to, false);
    int content = keywordEnd < 0 ? -1 : nonWhitespace(keywordEnd, to);
    if (content < 0) {
      throw new MarkupException("XML Declaration must at least contain a \"version\" attribute: " + outer, start);
    }
    boolean[] seen = new boolean[3]; // version, encoding, standalone
    attributes(content, to, (ns, ne, vs, ve, quote) -> {
      String name = str(ns, ne);
      String problem;
      if (name.equals("version")) {
        problem = seen[0] ? "can declare only one \"version\" attribute"
            : seen[1] || seen[2] ? "must declare \"version\" as its first attribute" : null;
        seen[0] = true;
      } else if (name.equals("encoding")) {
        problem = seen[1] ? "can declare only one \"encoding\" attribute"
            : !seen[0] ? "must declare \"encoding\" after \"version\""
            : seen[2] ? "must declare \"encoding\" before \"standalone\"" : null;
        seen[1] = true;
      } else if (name.equals("standalone")) {
        problem = seen[2] ? "can declare only one \"standalone\" attribute" : null;
        seen[2] = true;
      } else {
        problem = "does not allow attribute with name \"" + name
            + "\". Only \"version\", \"encoding\" and \"standalone\" are allowed (in that order)";
      }
      if (problem != null) {
        throw new MarkupException("XML Declaration " + problem + ": " + outer, start);
      }
    });
    if (!seen[0]) {
      throw new MarkupException("Attribute \"version\" is required in XML Declaration: " + outer, start);
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
      } else if (!inQuotes && !inApos && Character.isWhitespace(c)) {
        return i;
      }
    }
    return -1;
  }

  private int nonWhitespace(int from, int to) {
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(buf[i])) {
        return i;
      }
    }
    return -1;
  }

  /** The first {@code =} or whitespace. */
  private int operator(int from, int to) {
    for (int i = from; i < to; i++) {
      if (buf[i] == '=' || Character.isWhitespace(buf[i])) {
        return i;
      }
    }
    return -1;
  }

  /** The first char that is neither {@code =} nor whitespace. */
  private int nonOperator(int from, int to) {
    for (int i = from; i < to; i++) {
      if (buf[i] != '=' && !Character.isWhitespace(buf[i])) {
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
      if (c != u && c != Character.toLowerCase(u)) {
        return false;
      }
    }
    return true;
  }

  private String str(int start, int end) {
    return new String(buf, start, end - start);
  }
}
