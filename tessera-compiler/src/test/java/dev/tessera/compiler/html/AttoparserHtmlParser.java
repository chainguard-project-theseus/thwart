package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Comment;
import dev.tessera.compiler.model.DocType;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.attoparser.AbstractMarkupHandler;
import org.attoparser.MarkupParser;
import org.attoparser.ParseException;
import org.attoparser.config.ParseConfiguration;

/**
 * The attoparser-based HtmlParser from before #170, kept verbatim as the reference for
 * HtmlParserDifferentialTest. Parses a template into a {@link Document} using attoparser (non-validating,
 * non-normalizing, HTML-aware for void elements and raw-text elements). Records exact
 * positions for every element, attribute name and attribute value, decodes character
 * references in attribute values ({@code Attribute.value}) while keeping the raw form, and
 * classifies every text node's {@code OutputContext} via {@link Contexts}.
 *
 * <p>The content of {@code <script>}, {@code <style>}, {@code <textarea>} and {@code <title>}
 * is a single raw {@link Text} node: markup inside it is not parsed.
 *
 * <p>Build errors (reported, then parsing continues): unclosed or mismatched non-void elements,
 * duplicate attributes on one element, malformed markup, any start tag inside a {@code <select>}
 * other than {@code <option>}, {@code <optgroup>}, {@code <hr>} and {@code <t:block>}.
 */
final class AttoparserHtmlParser {

  private static final Set<String> RAW_TEXT_ELEMENTS = Backports.setOf("script", "style", "textarea", "title");
  private static final Pattern ELEMENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]*");
  private static final Map<String, String> NAMED_REFERENCES = new HashMap<>();

  static {
    String[] refs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " "};
    for (int i = 0; i < refs.length; i += 2) {
      NAMED_REFERENCES.put(refs[i], refs[i + 1]);
    }
  }

  private static final ParseConfiguration CONFIG = config();

  private static ParseConfiguration config() {
    ParseConfiguration c = ParseConfiguration.htmlConfiguration();
    // We balance elements and check attributes ourselves so errors carry our positions.
    c.setElementBalancing(ParseConfiguration.ElementBalancing.NO_BALANCING);
    c.setNoUnmatchedCloseElementsRequired(false);
    c.setUniqueAttributesInElementRequired(false);
    c.setUniqueRootElementPresence(ParseConfiguration.UniqueRootElementPresence.NOT_VALIDATED);
    c.setCaseSensitive(false);
    c.setTextSplittable(false);
    return c;
  }

  public Document parse(TemplateSource source, DiagnosticSink diagnostics) {
    char[] chars = source.content().toCharArray();
    Builder builder = new Builder(source, chars, diagnostics);
    try {
      new MarkupParser(CONFIG).parse(chars, builder);
      builder.finish(true);
    } catch (ParseException e) {
      int line = e.getLine() == null ? 1 : e.getLine();
      int col = e.getCol() == null ? 1 : e.getCol();
      diagnostics.error(new SourcePos(source.path(), line, col), "malformed markup: " + describe(e));
      builder.finish(false);
    }
    return new Document(source, builder.topLevel);
  }

  private static String describe(ParseException e) {
    String m = e.getMessage() == null ? "" : e.getMessage();
    m = m.replaceFirst("^\\(Line = \\d+, Column = \\d+\\)\\s*", "");
    int nl = m.indexOf('\n');
    if (nl >= 0) {
      m = m.substring(0, nl);
    }
    return m.length() > 80 ? m.substring(0, 80) + "..." : m;
  }

  /**
   * Decodes HTML character references: numeric ({@code &#106;}, {@code &#x6A;}, semicolon
   * optional) and the named references {@code amp lt gt quot apos nbsp} (semicolon required).
   * Unknown named references are left as written; invalid code points become U+FFFD.
   */
  static String decode(String raw) {
    if (raw.indexOf('&') < 0) {
      return raw;
    }
    StringBuilder out = new StringBuilder(raw.length());
    int i = 0;
    while (i < raw.length()) {
      char c = raw.charAt(i);
      if (c != '&') {
        out.append(c);
        i++;
        continue;
      }
      int end = decodeOne(raw, i, out);
      if (end < 0) {
        out.append('&');
        i++;
      } else {
        i = end;
      }
    }
    return out.toString();
  }

  /** Decodes the reference at {@code amp}; returns the index after it, or -1 if none. */
  private static int decodeOne(String s, int amp, StringBuilder out) {
    int i = amp + 1;
    if (i < s.length() && s.charAt(i) == '#') {
      i++;
      boolean hex = i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X');
      if (hex) {
        i++;
      }
      int start = i;
      long cp = 0;
      while (i < s.length() && Character.digit(s.charAt(i), hex ? 16 : 10) >= 0) {
        if (cp <= 0x10FFFF) {
          cp = cp * (hex ? 16 : 10) + Character.digit(s.charAt(i), hex ? 16 : 10);
        }
        i++;
      }
      if (i == start) {
        return -1;
      }
      if (i < s.length() && s.charAt(i) == ';') {
        i++;
      }
      boolean valid = cp > 0 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF);
      out.appendCodePoint(valid ? (int) cp : 0xFFFD);
      return i;
    }
    int semi = s.indexOf(';', i);
    if (semi < 0) {
      return -1;
    }
    String replacement = NAMED_REFERENCES.get(s.substring(i, semi));
    if (replacement == null) {
      return -1;
    }
    out.append(replacement);
    return semi + 1;
  }

  /** An element whose end tag has not been seen yet. */
  private static final class Open {
    final String name;
    final SourcePos pos;
    final List<Attribute> attributes = new ArrayList<>();
    final Set<String> attributeNames = new HashSet<>();
    final List<Node> children = new ArrayList<>();
    OutputContext textContext = OutputContext.TEXT;
    /** For a {@link Contexts#browserRawText} element, where a browser ends it; -1 if nowhere. */
    int browserEnd = -1;
    final String namespace;
    String contextNamespace;
    String contextName;

    Open(String name, SourcePos pos, Open parent) {
      this.name = name;
      this.pos = pos;
      this.contextNamespace = parent == null ? null : parent.contextNamespace;
      this.contextName = parent == null ? null : parent.contextName;
      this.namespace = HtmlParser.namespace(contextNamespace, contextName, name);
    }

    void opened() {
      if (HtmlParser.omitsTag(name, attributes)) {
        return;
      }
      boolean html = HtmlParser.htmlAnnotation(namespace, name, attributes);
      contextNamespace = html ? HtmlParser.HTML : namespace;
      contextName = html ? null : name;
    }

    Element toElement(boolean selfClosing, boolean hasEndTag) {
      return new Element(name, attributes, children, selfClosing, hasEndTag, pos,
          !namespace.equals(HtmlParser.HTML));
    }
  }

  private static final class Builder extends AbstractMarkupHandler {
    private final String file;
    private final String content;
    /** The buffer attoparser is reading; see {@link #matchEndTagCase}. */
    private final char[] chars;
    private final DiagnosticSink diagnostics;
    private final int[] lineStarts;
    final List<Node> topLevel = new ArrayList<>();
    private final Deque<Open> stack = new ArrayDeque<>();

    /** Start tag being read (between ElementStart and ElementEnd events). */
    private Open tag;

    /** Raw-text element whose content we are skipping, its content offset and position. */
    private Open raw;
    private int rawStart;
    /** Offset of the first {@code </name} that a browser would take as the raw element's end. */
    private int rawEnd;
    private SourcePos rawPos;

    private final StringBuilder text = new StringBuilder();
    private SourcePos textPos;

    Builder(TemplateSource source, char[] chars, DiagnosticSink diagnostics) {
      this.file = source.path();
      this.content = source.content();
      this.chars = chars;
      this.diagnostics = diagnostics;
      this.lineStarts = source.lineStarts();
    }

    private SourcePos pos(int line, int col) {
      return new SourcePos(file, line, col);
    }

    private int offset(int line, int col) {
      return lineStarts[line - 1] + col - 1;
    }

    private List<Node> children() {
      return stack.isEmpty() ? topLevel : stack.peek().children;
    }

    private OutputContext textContext() {
      return stack.isEmpty() ? OutputContext.TEXT : stack.peek().textContext;
    }

    private void flushText() {
      if (text.length() != 0) {
        int bogus = HtmlParser.bogusComment(text);
        if (bogus >= 0) {
          diagnostics.error(after(textPos, text, bogus), HtmlParser.bogusCommentMessage(text, bogus));
        }
        children().add(new Text(text.toString(), textContext(), textPos));
        text.setLength(0);
      }
    }

    @Override
    public void handleText(char[] buffer, int offset, int len, int line, int col) {
      if (raw != null) {
        return;
      }
      if (text.length() == 0) {
        textPos = pos(line, col);
      }
      text.append(buffer, offset, len);
    }

    @Override
    public void handleComment(
        char[] buffer, int contentOffset, int contentLen, int outerOffset, int outerLen, int line, int col) {
      if (raw != null) {
        return;
      }
      flushText();
      String comment = new String(buffer, outerOffset, outerLen);
      int early = HtmlParser.earlyCommentEnd(comment);
      if (early >= 0) {
        diagnostics.error(position(offset(line, col) + early), early == 0
            ? "malformed markup: a comment cannot start with '<!-->' or '<!--->' (a browser ends it there):"
                + " put a space after '<!--'"
            : "malformed markup: '--!>' ends a comment in a browser: end the comment with '-->' or remove the '!'");
      }
      children().add(new Comment(comment, pos(line, col)));
    }

    @Override
    public void handleCDATASection(
        char[] buffer, int contentOffset, int contentLen, int outerOffset, int outerLen, int line, int col) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(new String(buffer, outerOffset, outerLen), line, col);
      children().add(new Text(new String(buffer, outerOffset, outerLen), textContext(), pos(line, col)));
    }

    @Override
    public void handleDocType(
        char[] buffer,
        int keywordOffset, int keywordLen, int keywordLine, int keywordCol,
        int elementNameOffset, int elementNameLen, int elementNameLine, int elementNameCol,
        int typeOffset, int typeLen, int typeLine, int typeCol,
        int publicIdOffset, int publicIdLen, int publicIdLine, int publicIdCol,
        int systemIdOffset, int systemIdLen, int systemIdLine, int systemIdCol,
        int internalSubsetOffset, int internalSubsetLen, int internalSubsetLine, int internalSubsetCol,
        int outerOffset, int outerLen, int outerLine, int outerCol) {
      declaration(buffer, outerOffset, outerLen, outerLine, outerCol);
    }

    @Override
    public void handleXmlDeclaration(
        char[] buffer,
        int keywordOffset, int keywordLen, int keywordLine, int keywordCol,
        int versionOffset, int versionLen, int versionLine, int versionCol,
        int encodingOffset, int encodingLen, int encodingLine, int encodingCol,
        int standaloneOffset, int standaloneLen, int standaloneLine, int standaloneCol,
        int outerOffset, int outerLen, int line, int col) {
      declaration(buffer, outerOffset, outerLen, line, col);
    }

    @Override
    public void handleProcessingInstruction(
        char[] buffer,
        int targetOffset, int targetLen, int targetLine, int targetCol,
        int contentOffset, int contentLen, int contentLine, int contentCol,
        int outerOffset, int outerLen, int line, int col) {
      declaration(buffer, outerOffset, outerLen, line, col);
    }

    private void declaration(char[] buffer, int offset, int len, int line, int col) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(new String(buffer, offset, len), line, col);
      children().add(new DocType(new String(buffer, offset, len), pos(line, col)));
    }

    private void checkEarlyEnd(String outer, int line, int col) {
      int gt = HtmlParser.earlyEnd(outer);
      if (gt < 0) {
        return;
      }
      diagnostics.error(after(pos(line, col), outer, gt), HtmlParser.earlyEndMessage(outer));
    }

    /** The position of char {@code to} of {@code s}, which starts at {@code start}. */
    private SourcePos after(SourcePos start, CharSequence s, int to) {
      int line = start.line();
      int col = start.column();
      for (int i = 0; i < to; i++) {
        if (s.charAt(i) == '\n') {
          line++;
          col = 1;
        } else {
          col++;
        }
      }
      return pos(line, col);
    }

    @Override
    public void handleStandaloneElementStart(
        char[] buffer, int offset, int len, boolean minimized, int line, int col) {
      startTag(buffer, offset, len, line, col);
    }

    @Override
    public void handleOpenElementStart(char[] buffer, int offset, int len, int line, int col) {
      startTag(buffer, offset, len, line, col);
    }

    private void startTag(char[] buffer, int offset, int len, int line, int col) {
      if (raw != null) {
        return;
      }
      flushText();
      String name = new String(buffer, offset, len);
      tag = new Open(name, pos(line, col), stack.peek());
      if (name.equalsIgnoreCase("plaintext")) {
        diagnostics.error(tag.pos, "<" + name + "> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>");
      }
      if (!ELEMENT_NAME.matcher(name).matches()) {
        diagnostics.error(tag.pos, "malformed markup: invalid element name '" + name
            + "': element names start with a letter and use letters, digits, '-', '_', ':' and '.'");
      }
      String lower = name.toLowerCase(Locale.ROOT);
      if (!HtmlParser.SELECT_CONTENT.contains(lower) && inSelect()) {
        diagnostics.error(tag.pos, HtmlParser.notInSelect(lower));
      }
    }

    private boolean inSelect() {
      for (Open o : stack) {
        if (o.name.equalsIgnoreCase("select")) {
          return true;
        }
      }
      return false;
    }

    @Override
    public void handleAttribute(
        char[] buffer,
        int nameOffset, int nameLen, int nameLine, int nameCol,
        int operatorOffset, int operatorLen, int operatorLine, int operatorCol,
        int valueContentOffset, int valueContentLen, int valueOuterOffset, int valueOuterLen,
        int valueLine, int valueCol) {
      if (raw != null || tag == null) {
        return;
      }
      String name = new String(buffer, nameOffset, nameLen);
      SourcePos namePos = pos(nameLine, nameCol);
      if (name.chars().anyMatch(ch -> ch == '<' || ch == '"' || ch == '\'' || ch == '=')) {
        diagnostics.error(namePos, "malformed markup: invalid attribute name '" + name
            + "': attribute names cannot contain <, quotes or =");
      }
      if (!tag.attributeNames.add(name.toLowerCase(Locale.ROOT))) {
        diagnostics.error(namePos, "duplicate attribute '" + name + "' on <" + tag.name + ">: remove one of them");
      }
      Attribute attribute;
      if (operatorLen == 0) {
        attribute = new Attribute(name, null, null, (char) 0, namePos, null);
      } else {
        String rawValue = new String(buffer, valueContentOffset, valueContentLen);
        boolean quoted = valueOuterLen == valueContentLen + 2;
        char quote = quoted ? buffer[valueOuterOffset] : 0;
        SourcePos valuePos = pos(valueLine, quoted ? valueCol + 1 : valueCol);
        attribute = new Attribute(name, decode(rawValue), rawValue, quote, namePos, valuePos);
      }
      tag.attributes.add(attribute);
    }

    @Override
    public void handleStandaloneElementEnd(
        char[] buffer, int offset, int len, boolean minimized, int line, int col) {
      if (raw != null || tag == null) {
        return;
      }
      String lower = tag.name.toLowerCase(Locale.ROOT);
      if (minimized && (lower.equals("script") || lower.equals("style") || lower.equals("select"))) {
        // A browser treats <script/> as an open tag, so the template text after it would run as
        // script (or style, or select content) while Tessera escaped it for TEXT. <textarea/>, <title/>
        // and the other raw text elements are checked where Tessera writes them (TemplateGenerator).
        diagnostics.error(tag.pos, "<" + lower + "/> is not self-closing in HTML: the browser treats everything"
            + " after it as " + lower + "; write <" + lower + " ...></" + lower + ">");
      }
      checkBreakout(tag);
      children().add(tag.toElement(minimized, false));
      tag = null;
    }

    /** Reports a breakout tag ({@link HtmlParser#breaksOut}) inside svg or math. */
    private void checkBreakout(Open open) {
      if (HtmlParser.breaksOut(open.namespace, open.name, open.attributes)) {
        diagnostics.error(open.pos, "malformed markup: <" + open.name + "> inside <svg> or <math> is HTML in a"
            + " browser and ends the <svg> or <math> there: close it first, or put HTML inside <foreignObject>");
      }
    }

    @Override
    public void handleOpenElementEnd(char[] buffer, int offset, int len, int line, int col) {
      if (raw != null || tag == null) {
        return;
      }
      Open open = tag;
      tag = null;
      open.opened();
      checkBreakout(open);
      open.textContext = Contexts.text(open.toElement(false, false));
      stack.push(open);
      if (RAW_TEXT_ELEMENTS.contains(open.name.toLowerCase(Locale.ROOT))) {
        // line:col is the '>' of the start tag; content starts right after it.
        raw = open;
        rawStart = offset(line, col) + 1;
        rawPos = pos(line, col + 1);
        rawEnd = browserEndTag(open.name, rawStart);
        matchEndTagCase(open.name);
      } else if (Contexts.browserRawText(open.name)) {
        open.browserEnd = browserEndTag(open.name, offset(line, col) + 1);
      }
    }

    /**
     * attoparser ends raw text only at {@code </name>} spelled in the start tag's case, while
     * browsers match the end tag case-insensitively ({@code <Script>...</sCrIpT>}). Rewrites the
     * name of the end tag a browser would use, in attoparser's not-yet-read input, to the start
     * tag's case. Lengths and offsets are unchanged and node text comes from {@link #content},
     * so only where attoparser stops changes.
     */
    private void matchEndTagCase(String name) {
      if (rawEnd >= 0) {
        name.getChars(0, name.length(), chars, rawEnd + 2);
      }
    }

    @Override
    public void handleCloseElementStart(char[] buffer, int offset, int len, int line, int col) {
      closeTag(new String(buffer, offset, len), line, col);
    }

    @Override
    public void handleUnmatchedCloseElementStart(char[] buffer, int offset, int len, int line, int col) {
      closeTag(new String(buffer, offset, len), line, col);
    }

    private void closeTag(String name, int line, int col) {
      SourcePos closePos = pos(line, col);
      if (raw != null) {
        if (!HtmlParser.asciiLower(raw.name).equals(HtmlParser.asciiLower(name))) {
          return;
        }
        int end = offset(line, col);
        if (end != rawEnd) {
          // attoparser parsed markup (a comment, an attribute value) that hid an earlier end
          // tag; a browser would end the element there, so the contexts after it would be wrong.
          hiddenEndTag(raw.name, rawEnd);
        }
        endRaw(end);
      }
      flushText();
      boolean open = stack.stream().anyMatch(o -> o.name.equalsIgnoreCase(name));
      if (!open) {
        diagnostics.error(closePos, "end tag </" + name + "> does not match any open element: remove it or add the"
            + " matching <" + name + ">");
        return;
      }
      while (true) {
        Open top = stack.pop();
        if (top.name.equalsIgnoreCase(name)) {
          if (top.browserEnd >= 0 && top.browserEnd != offset(line, col)) {
            // A comment, attribute value, nested raw text or a nested <noscript> hid the end tag
            // a browser uses: it would end the element there and parse the rest as markup.
            hiddenEndTag(top.name, top.browserEnd);
          }
          children().add(top.toElement(false, true));
          return;
        }
        diagnostics.error(top.pos, "element <" + top.name + "> is not closed (found </" + name + "> at "
            + line + ":" + col + "): add </" + top.name + ">");
        children().add(top.toElement(false, false));
      }
    }

    private void hiddenEndTag(String name, int offset) {
      String fix = Contexts.browserRawText(name)
          ? "a browser ends the element there, so the markup after it would be read differently; remove it or"
              + " move it outside the element"
          : "escape it (in a script string, write '<\\/" + name + "')";
      diagnostics.error(position(offset), "malformed markup: content of <" + name + "> contains '</" + name
          + "' before its end tag: " + fix);
    }

    /**
     * Offset of the first {@code </name} followed by whitespace, {@code /} or {@code >} at or
     * after {@code from} (ASCII case-insensitive), or -1.
     */
    private int browserEndTag(String name, int from) {
      String needle = "</" + name;
      for (int i = from; i + needle.length() <= content.length(); i++) {
        if (asciiMatchesIgnoreCase(content, i, needle)) {
          int next = i + needle.length();
          if (next == content.length() || " \t\n\f\r/>".indexOf(content.charAt(next)) >= 0) {
            return i;
          }
        }
      }
      return -1;
    }

    /** Whether {@code s} has {@code needle} at {@code at}, folding ASCII letters only, as browsers do. */
    private static boolean asciiMatchesIgnoreCase(String s, int at, String needle) {
      for (int i = 0; i < needle.length(); i++) {
        char a = s.charAt(at + i);
        char b = needle.charAt(i);
        if (a != b && asciiLower(a) != asciiLower(b)) {
          return false;
        }
      }
      return true;
    }

    private static char asciiLower(char c) {
      return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private SourcePos position(int offset) {
      int line = 0;
      while (line + 1 < lineStarts.length && lineStarts[line + 1] <= offset) {
        line++;
      }
      return pos(line + 1, offset - lineStarts[line] + 1);
    }

    /** Ends the current raw-text element's content at {@code end} (exclusive). */
    private void endRaw(int end) {
      if (end > rawStart) {
        String body = content.substring(rawStart, end);
        int comment = body.indexOf("<!--");
        int confusable = HtmlParser.confusableEndTag(body, raw.name);
        if (confusable >= 0) {
          // It reads as the end tag, but a browser goes on past it. Fail closed.
          diagnostics.error(position(rawStart + confusable), "malformed markup: '"
              + body.substring(confusable, confusable + 2 + raw.name.length()) + "' inside <" + raw.name
              + "> looks like its end tag but a browser doesn't end the element there: remove it");
        }
        boolean foreign = !raw.namespace.equals(HtmlParser.HTML);
        int markup = foreign ? HtmlParser.foreignMarkup(body) : -1;
        int reference = foreign && markup < 0 && (raw.name.equalsIgnoreCase("script")
            || raw.name.equalsIgnoreCase("style")) ? HtmlParser.foreignReference(body) : -1;
        if (markup >= 0) {
          diagnostics.error(position(rawStart + markup), "malformed markup: '<' inside <" + raw.name
              + "> in <svg> or <math> starts markup in a browser, not text: write &lt; for a literal '<'");
        } else if (reference >= 0) {
          diagnostics.error(position(rawStart + reference), "malformed markup: '&' inside <" + raw.name
              + "> in <svg> or <math> is decoded by a browser: write the character itself, or use a CDATA section");
        } else if (raw.name.equalsIgnoreCase("script") && comment >= 0) {
          // Browsers apply script-data escape rules after "<!--" ("<script" inside it hides
          // the next "</script>"); attoparser does not. Fail closed.
          diagnostics.error(position(rawStart + comment),
              "malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)");
        }
        raw.children.add(new Text(body, raw.textContext, rawPos));
      }
      raw = null;
    }

    /** Closes everything still open at the end of input; reports them if parsing completed. */
    void finish(boolean report) {
      if (raw != null) {
        endRaw(content.length());
      }
      flushText();
      while (!stack.isEmpty()) {
        Open top = stack.pop();
        if (report) {
          diagnostics.error(top.pos, "element <" + top.name + "> is not closed: add </" + top.name + ">");
        }
        children().add(top.toElement(false, false));
      }
    }
  }
}
