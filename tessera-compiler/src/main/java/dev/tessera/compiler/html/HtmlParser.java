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

/**
 * Parses a template into a {@link Document} using {@link MarkupTokenizer} (non-validating,
 * non-normalizing, HTML-aware for void elements and raw-text elements). Records exact
 * positions for every element, attribute name and attribute value, decodes character
 * references in attribute values ({@code Attribute.value}) while keeping the raw form, and
 * classifies every text node's {@code OutputContext} via {@link Contexts}.
 *
 * <p>The content of {@code <script>}, {@code <style>}, {@code <textarea>} and {@code <title>}
 * is a single raw {@link Text} node: markup inside it is not parsed.
 *
 * <p>The content of {@code <xmp>}, {@code <iframe>}, {@code <noembed>}, {@code <noframes>} and
 * {@code <noscript>} is parsed as markup, but a browser reads it as raw text (for {@code
 * <noscript>}, when scripting is on) up to the first {@code </name}. That must be the element's
 * own end tag, so both readings end it at the same place. {@code <plaintext>} is rejected.
 *
 * <p>Build errors (reported, then parsing continues): unclosed or mismatched non-void elements,
 * duplicate attributes on one element, malformed markup (including markup a browser would end
 * earlier than the tokenizer does).
 */
public final class HtmlParser {

  private static final Set<String> RAW_TEXT_ELEMENTS = Backports.setOf("script", "style", "textarea", "title");
  private static final Pattern ELEMENT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]*");
  private static final Map<String, String> NAMED_REFERENCES = new HashMap<>();

  static {
    String[] refs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " "};
    for (int i = 0; i < refs.length; i += 2) {
      NAMED_REFERENCES.put(refs[i], refs[i + 1]);
    }
  }

  /**
   * True if the content of an element named {@code name} (any case) is parsed as one raw text
   * node ({@code <script>}, {@code <style>}, {@code <textarea>}, {@code <title>}): a browser
   * reads it as text only inside that element, never as markup elsewhere.
   */
  public static boolean isRawText(String name) {
    return RAW_TEXT_ELEMENTS.contains(name.toLowerCase(Locale.ROOT));
  }

  public Document parse(TemplateSource source, DiagnosticSink diagnostics) {
    char[] chars = source.content().toCharArray();
    Builder builder = new Builder(source, chars, diagnostics);
    try {
      new MarkupTokenizer(chars, builder).run();
      builder.finish(true);
    } catch (MarkupTokenizer.MarkupException e) {
      diagnostics.error(builder.position(e.offset), "malformed markup: " + describe(e.getMessage()));
      builder.finish(false);
    }
    return new Document(source, builder.topLevel);
  }

  /** The message's first line, at most 80 chars. */
  private static String describe(String m) {
    int nl = m.indexOf('\n');
    if (nl >= 0) {
      m = m.substring(0, nl);
    }
    return m.length() > 80 ? m.substring(0, 80) + "..." : m;
  }

  /**
   * Index of a {@code >} before the last char of {@code outer}, a CDATA section, DOCTYPE, XML
   * declaration or processing instruction as the tokenizer read it, or -1. A browser ends each of
   * these at its first {@code >} (outside svg and math, a CDATA section is a bogus comment; a
   * {@code <?...>} always is), quotes and brackets included, so the contexts after an earlier
   * {@code >} would differ from Tessera's.
   */
  static int earlyEnd(String outer) {
    int gt = outer.indexOf('>');
    return gt < outer.length() - 1 ? gt : -1;
  }

  /**
   * Index in {@code text}, element text the tokenizer did not read as markup, of a {@code <!},
   * {@code <?} or {@code </} not followed by an ASCII letter or {@code >}, or -1. A browser starts
   * a bogus comment there that ends at the first {@code >}, hiding markup Tessera reads.
   */
  static int bogusComment(CharSequence text) {
    for (int i = 0; i + 1 < text.length(); i++) {
      if (text.charAt(i) != '<') {
        continue;
      }
      char c = text.charAt(i + 1);
      if (c == '!' || c == '?') {
        return i;
      }
      if (c == '/') {
        char d = i + 2 < text.length() ? text.charAt(i + 2) : ' ';
        if (!(d >= 'a' && d <= 'z' || d >= 'A' && d <= 'Z' || d == '>')) {
          return i;
        }
      }
    }
    return -1;
  }

  /** The build error for the {@link #bogusComment} at {@code i} of {@code text}. */
  static String bogusCommentMessage(CharSequence text, int i) {
    char c = text.charAt(i + 1);
    String what = c == '!' ? "'<!'" : c == '?' ? "'<?'" : "'</' not followed by a letter";
    return "malformed markup: " + what + " starts a comment in a browser that ends at the first '>':"
        + " write &lt; for a literal '<'";
  }

  /**
   * Index in the CDATA section {@code outer} of the first {@code [[} or {@code [(}, which {@code
   * TextInlining} would render as an expression, or -1. Outside svg and math a browser reads the
   * section as a comment, and inside them as text that does not decode {@code &lt;}; in neither is
   * the value element text, so no escaping fits.
   */
  static int cdataHole(String outer) {
    int a = outer.indexOf("[[");
    int b = outer.indexOf("[(");
    return a < 0 ? b : b < 0 ? a : Math.min(a, b);
  }

  /** The build error when {@link #cdataHole} finds an expression. */
  static final String CDATA_HOLE_MESSAGE =
      "malformed markup: [[...]] and [(...)] are not supported inside a CDATA section: outside <svg> and <math>"
          + " a browser reads the section as a comment, and inside them it shows &lt; as written; write the text"
          + " without <![CDATA[";

  /** The build error for {@code outer} when {@link #earlyEnd} finds a {@code >}. */
  static String earlyEndMessage(String outer) {
    if (outer.startsWith("<![")) {
      return "malformed markup: '>' inside a CDATA section is not supported, even in <svg> and <math>: elsewhere a"
          + " browser ends it at the first '>'; write the text without <![CDATA[, with '>' as &gt;";
    }
    if (outer.startsWith("<?")) {
      return "malformed markup: '>' inside '<?...?>' is not supported: a browser ends it at the first '>';"
          + " remove the '>'";
    }
    return "malformed markup: '>' inside <!DOCTYPE> is not supported: a browser ends it at the first '>';"
        + " write <!DOCTYPE html>";
  }

  /**
   * Decodes HTML character references: numeric ({@code &#106;}, {@code &#x6A;}, semicolon
   * optional) and the named references {@code amp lt gt quot apos nbsp} (semicolon required).
   * Unknown named references are left as written; invalid code points become U+FFFD.
   */
  public static String decode(String raw) {
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

  /**
   * The offset in {@code comment} where a browser ends it before its last {@code >}, or -1.
   * {@code comment} runs from {@code <!--} through the first {@code -->} at least 7 chars in, as
   * the tokenizer reads it. The HTML tokenizer also ends a comment at {@code <!-->}, at {@code
   * <!--->} and at a {@code --!>} whose dashes come after {@code <!--}; any of these in {@code
   * comment} means a browser reads the markup after it differently (offset 0 for the first two).
   */
  static int earlyCommentEnd(String comment) {
    if (comment.startsWith("<!-->") || comment.startsWith("<!--->")) {
      return 0;
    }
    return comment.indexOf("--!>", 4);
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

    Open(String name, SourcePos pos) {
      this.name = name;
      this.pos = pos;
    }

    Element toElement(boolean selfClosing, boolean hasEndTag) {
      return new Element(name, attributes, children, selfClosing, hasEndTag, pos);
    }
  }

  private static final class Builder implements MarkupTokenizer.Handler {
    private final String file;
    private final String content;
    /** The buffer the tokenizer is reading; see {@link #matchEndTagCase}. */
    private final char[] chars;
    private final DiagnosticSink diagnostics;
    private final int[] lineStarts;
    final List<Node> topLevel = new ArrayList<>();
    private final Deque<Open> stack = new ArrayDeque<>();

    /** Start tag being read (between startTag and its end). */
    private Open tag;

    /** Raw-text element whose content we are skipping, its content offset and position. */
    private Open raw;
    private int rawStart;
    /** Offset of the first {@code </name} that a browser would take as the raw element's end. */
    private int rawEnd;

    private final StringBuilder text = new StringBuilder();
    private SourcePos textPos;
    private int textStart;

    Builder(TemplateSource source, char[] chars, DiagnosticSink diagnostics) {
      this.file = source.path();
      this.content = source.content();
      this.chars = chars;
      this.diagnostics = diagnostics;
      this.lineStarts = source.lineStarts();
    }

    /** Line and column of {@code offset}: only LF starts a line, every other char is one column. */
    SourcePos position(int offset) {
      int lo = 0;
      int hi = lineStarts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return new SourcePos(file, lo + 1, offset - lineStarts[lo] + 1);
    }

    private String str(int start, int end) {
      return new String(chars, start, end - start);
    }

    private List<Node> children() {
      return stack.isEmpty() ? topLevel : stack.peek().children;
    }

    private OutputContext textContext() {
      return stack.isEmpty() ? OutputContext.TEXT : stack.peek().textContext;
    }

    private void flushText() {
      if (text.length() != 0) {
        int bogus = bogusComment(text);
        if (bogus >= 0) {
          diagnostics.error(position(textStart + bogus), bogusCommentMessage(text, bogus));
        }
        children().add(new Text(text.toString(), textContext(), textPos));
        text.setLength(0);
      }
    }

    @Override
    public void text(int start, int end) {
      if (raw != null) {
        return;
      }
      if (text.length() == 0) {
        textPos = position(start);
        textStart = start;
      }
      text.append(chars, start, end - start);
    }

    @Override
    public void comment(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      String comment = str(start, end);
      int early = earlyCommentEnd(comment);
      if (early >= 0) {
        diagnostics.error(position(start + early), early == 0
            ? "malformed markup: a comment cannot start with '<!-->' or '<!--->' (a browser ends it there):"
                + " put a space after '<!--'"
            : "malformed markup: '--!>' ends a comment in a browser: end the comment with '-->' or remove the '!'");
      }
      children().add(new Comment(comment, position(start)));
    }

    @Override
    public void cdata(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(start, end);
      int hole = cdataHole(str(start, end));
      if (hole >= 0) {
        diagnostics.error(position(start + hole), CDATA_HOLE_MESSAGE);
      }
      children().add(new Text(str(start, end), textContext(), position(start)));
    }

    @Override
    public void declaration(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      checkEarlyEnd(start, end);
      children().add(new DocType(str(start, end), position(start)));
    }

    private void checkEarlyEnd(int start, int end) {
      String outer = str(start, end);
      int gt = earlyEnd(outer);
      if (gt >= 0) {
        diagnostics.error(position(start + gt), earlyEndMessage(outer));
      }
    }

    @Override
    public void startTag(int lt, int nameEnd) {
      if (raw != null) {
        return;
      }
      flushText();
      String name = str(lt + 1, nameEnd);
      tag = new Open(name, position(lt));
      if (name.equalsIgnoreCase("plaintext")) {
        diagnostics.error(tag.pos, "<" + name + "> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>");
      }
      if (!ELEMENT_NAME.matcher(name).matches()) {
        diagnostics.error(tag.pos, "malformed markup: invalid element name '" + name
            + "': element names start with a letter and use letters, digits, '-', '_', ':' and '.'");
      }
    }

    @Override
    public void attribute(int nameStart, int nameEnd, int valueStart, int valueEnd, char quote) {
      if (raw != null || tag == null) {
        return;
      }
      String name = str(nameStart, nameEnd);
      SourcePos namePos = position(nameStart);
      if (name.chars().anyMatch(ch -> ch == '<' || ch == '"' || ch == '\'' || ch == '=')) {
        diagnostics.error(namePos, "malformed markup: invalid attribute name '" + name
            + "': attribute names cannot contain <, quotes or =");
      }
      if (!tag.attributeNames.add(name.toLowerCase(Locale.ROOT))) {
        diagnostics.error(namePos, "duplicate attribute '" + name + "' on <" + tag.name + ">: remove one of them");
      }
      Attribute attribute;
      if (valueStart < 0) {
        attribute = new Attribute(name, null, null, (char) 0, namePos, null);
      } else {
        String rawValue = str(valueStart, valueEnd);
        attribute = new Attribute(name, decode(rawValue), rawValue, quote, namePos, position(valueStart));
      }
      tag.attributes.add(attribute);
    }

    @Override
    public void selfClosedTagEnd(boolean minimized) {
      if (raw != null || tag == null) {
        return;
      }
      String lower = tag.name.toLowerCase(Locale.ROOT);
      if (minimized && (lower.equals("script") || lower.equals("style"))) {
        // A browser treats <script/> as an open tag, so the template text after it would run as
        // script (or style) while Tessera escaped it for TEXT. <textarea/>, <title/> and the other
        // raw text elements are checked where Tessera writes them (TemplateGenerator).
        diagnostics.error(tag.pos, "<" + lower + "/> is not self-closing in HTML: the browser treats everything"
            + " after it as " + lower + "; write <" + lower + " ...></" + lower + ">");
      }
      children().add(tag.toElement(minimized, false));
      tag = null;
    }

    @Override
    public void openTagEnd(int gt) {
      if (raw != null || tag == null) {
        return;
      }
      Open open = tag;
      tag = null;
      open.textContext = Contexts.text(open.toElement(false, false));
      stack.push(open);
      if (RAW_TEXT_ELEMENTS.contains(open.name.toLowerCase(Locale.ROOT))) {
        raw = open;
        rawStart = gt + 1;
        rawEnd = browserEndTag(open.name, rawStart);
        matchEndTagCase(open.name);
      } else if (Contexts.browserRawText(open.name)) {
        open.browserEnd = browserEndTag(open.name, gt + 1);
      }
    }

    /**
     * The tokenizer ends script and style content only at {@code </name>} spelled in the start
     * tag's case, while browsers match the end tag case-insensitively ({@code <Script>...</sCrIpT>}).
     * Rewrites the name of the end tag a browser would use, in the tokenizer's not-yet-read input,
     * to the start tag's case. Lengths and offsets are unchanged and node text comes from {@link
     * #content}, so only where the tokenizer stops (and the text its messages quote) changes.
     */
    private void matchEndTagCase(String name) {
      if (rawEnd >= 0) {
        name.getChars(0, name.length(), chars, rawEnd + 2);
      }
    }

    @Override
    public boolean rawText() {
      return raw != null;
    }

    @Override
    public void endTag(int lt, int nameEnd) {
      String name = str(lt + 2, nameEnd);
      SourcePos closePos = position(lt);
      if (raw != null) {
        if (!raw.name.equalsIgnoreCase(name)) {
          return;
        }
        if (lt != rawEnd) {
          // The tokenizer read markup (a comment, an attribute value) that hid an earlier end
          // tag; a browser would end the element there, so the contexts after it would be wrong.
          hiddenEndTag(raw.name, rawEnd);
        }
        endRaw(lt);
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
          if (top.browserEnd >= 0 && top.browserEnd != lt) {
            // A comment, attribute value, nested raw text or a nested <noscript> hid the end tag
            // a browser uses: it would end the element there and parse the rest as markup.
            hiddenEndTag(top.name, top.browserEnd);
          }
          children().add(top.toElement(false, true));
          return;
        }
        diagnostics.error(top.pos, "element <" + top.name + "> is not closed (found </" + name + "> at "
            + closePos.line() + ":" + closePos.column() + "): add </" + top.name + ">");
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

    /** Ends the current raw-text element's content at {@code end} (exclusive). */
    private void endRaw(int end) {
      if (end > rawStart) {
        String body = content.substring(rawStart, end);
        int comment = body.indexOf("<!--");
        if (raw.name.equalsIgnoreCase("script") && comment >= 0) {
          // Browsers apply script-data escape rules after "<!--" ("<script" inside it hides
          // the next "</script>"); the tokenizer does not. Fail closed.
          diagnostics.error(position(rawStart + comment),
              "malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)");
        }
        raw.children.add(new Text(body, raw.textContext, position(rawStart)));
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
