package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.expr.ExpressionParser;
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
 * is a single raw {@link Text} node: markup inside it is not parsed. Inside {@code <svg>} and
 * {@code <math>} a browser does parse it, so there that content must not contain markup (see
 * {@link #foreignMarkup}), nor, in a script or style, character references (see {@link
 * #foreignReference}); elements there are {@link Element#foreign foreign}.
 *
 * <p>The content of {@code <xmp>}, {@code <iframe>}, {@code <noembed>}, {@code <noframes>} and
 * {@code <noscript>} is parsed as markup, but a browser reads it as raw text (for {@code
 * <noscript>}, when scripting is on) up to the first {@code </name}. That must be the element's
 * own end tag, so both readings end it at the same place. {@code <plaintext>} is rejected.
 *
 * <p>Build errors (reported, then parsing continues): unclosed or mismatched non-void elements,
 * duplicate attributes on one element, malformed markup (including markup a browser would end
 * earlier than the tokenizer does), any start tag inside a {@code <select>} other than
 * {@code <option>}, {@code <optgroup>}, {@code <hr>} and {@code <t:block>}.
 */
public final class HtmlParser {

  private static final Set<String> RAW_TEXT_ELEMENTS = Backports.setOf("script", "style", "textarea", "title");
  /** Starts a Thymeleaf prototype-only comment, whose content Thymeleaf renders (not supported). */
  private static final String PROTOTYPE_COMMENT = "<!--/*/";
  private static final String PROTOTYPE_COMMENT_MESSAGE = "Thymeleaf prototype-only comments '<!--/*/ ... /*/-->'"
      + " are not supported: remove the '<!--/*/' and '/*/-->' markers to make the content part of the"
      + " template";
  /**
   * The start tags allowed anywhere inside a select. Browsers with the classic "in select"
   * insertion mode (Firefox 140) ignore most other start tags there, svg and math included, and
   * parse their content as markup: a {@code <script>} in a {@code <title>}, {@code <style>} or
   * svg CDATA runs. The {@code <t:block>} tag is never rendered.
   */
  static final Set<String> SELECT_CONTENT = Backports.setOf("option", "optgroup", "hr", "t:block");
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

  /** Namespaces of {@link #namespace}. */
  static final String HTML = "html";
  static final String SVG = "svg";
  static final String MATHML = "math";

  /** SVG elements whose children a browser parses as HTML (HTML integration points). */
  private static final Set<String> SVG_HTML_PARENTS = Backports.setOf("foreignobject", "desc", "title");
  /** MathML text integration points: their children are HTML, except {@code mglyph}/{@code malignmark}. */
  private static final Set<String> MATHML_TEXT_PARENTS = Backports.setOf("mi", "mo", "mn", "ms", "mtext");

  /**
   * The namespace a browser gives element {@code name} whose nearest rendered parent (see {@link
   * #omitsTag}) is element {@code parentName} of namespace {@code parentNs} (null at the top level,
   * which is HTML): {@link #SVG} inside {@code <svg>} and {@link #MATHML} inside {@code <math>}
   * until an HTML integration point (SVG {@code foreignObject}, {@code desc}, {@code title}; MathML
   * {@code mi mo mn ms mtext}, and {@code annotation-xml} with an HTML {@code encoding}, see {@link
   * #htmlAnnotation}). A breakout tag such as {@code <p>} in foreign content is a build error
   * ({@link #breaksOut}).
   */
  static String namespace(String parentNs, String parentName, String name) {
    String parent = parentName == null ? null : asciiLower(parentName);
    String el = asciiLower(name);
    if (SVG.equals(parentNs) && !SVG_HTML_PARENTS.contains(parent)) {
      return SVG;
    }
    if (MATHML.equals(parentNs) && !MATHML_TEXT_PARENTS.contains(parent)) {
      return "annotation-xml".equals(parent) && el.equals("svg") ? SVG : MATHML;
    }
    if (MATHML.equals(parentNs) && (el.equals("mglyph") || el.equals("malignmark"))) {
      return MATHML;
    }
    return el.equals("svg") ? SVG : el.equals("math") ? MATHML : HTML;
  }

  /** {@code s} with only A-Z lowercased, as a browser compares names and encodings. */
  static String asciiLower(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return sb.toString();
  }

  /**
   * Whether MathML element {@code name} is an {@code annotation-xml} that a browser parses as an
   * HTML integration point: its static {@code encoding} is {@code text/html} or {@code
   * application/xhtml+xml} (ASCII case-insensitive), and no {@code t:encoding} or {@code t:attr}
   * {@link #attrMaySet may set} it. Otherwise its children are MathML.
   */
  static boolean htmlAnnotation(String namespace, String name, List<Attribute> attributes) {
    if (!namespace.equals(MATHML) || !asciiLower(name).equals("annotation-xml")) {
      return false;
    }
    boolean html = false;
    for (Attribute a : attributes) {
      String local = asciiLower(a.localName());
      if (a.isTessera() && (local.equals("encoding") || attrMaySet(a, "encoding"))) {
        return false;
      }
      if (!a.isTessera() && local.equals("encoding") && a.value() != null) {
        String v = asciiLower(a.value());
        html = v.equals("text/html") || v.equals("application/xhtml+xml");
      }
    }
    return html;
  }

  /**
   * Whether {@code a} is a {@code t:attr*} attribute that may set attribute {@code name}
   * (lower-case): a {@code t:attr} sets only the attributes it names, which the template writes as
   * literals ({@code t:attr="id=${s}"} cannot set {@code encoding}). A {@code t:attr} that does not
   * parse, and any other {@code t:attr*}, may set anything; AttrProcessor reports the parse error.
   */
  static boolean attrMaySet(Attribute a, String name) {
    String local = asciiLower(a.localName());
    if (!a.isTessera() || !local.startsWith("attr")) {
      return false;
    }
    if (!local.equals("attr")) {
      return true;
    }
    if (a.value() == null) {
      return false;
    }
    List<Assignment> assignments = new ExpressionParser().parseAssignments(a.value(), a.valuePos(), d -> {});
    if (assignments == null) {
      return true;
    }
    for (Assignment assignment : assignments) {
      if (asciiLower(assignment.name()).equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** Start tags that end foreign content in a browser; {@code font} only with color, face or size. */
  private static final Set<String> BREAKOUT_TAGS =
      Backports.setOf(
          "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed",
          "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta",
          "nobr", "ol", "p", "pre", "ruby", "s", "small", "span", "strike", "strong", "sub", "sup", "table",
          "tt", "u", "ul", "var");

  /**
   * Whether a rendered element {@code name} of foreign {@code namespace} is a breakout tag: a
   * browser closes the open {@code <svg>}/{@code <math>} elements there and parses it as HTML, so
   * the contexts after it would differ from Tessera's. A {@code <font>} counts when it has a
   * {@code color}, {@code face} or {@code size}, or a {@code t:attr} that {@link #attrMaySet may set} one.
   */
  static boolean breaksOut(String namespace, String name, List<Attribute> attributes) {
    if (namespace.equals(HTML) || omitsTag(name, attributes)) {
      return false;
    }
    String el = asciiLower(name);
    if (BREAKOUT_TAGS.contains(el)) {
      return true;
    }
    if (!el.equals("font")) {
      return false;
    }
    for (Attribute a : attributes) {
      String local = asciiLower(a.localName());
      if (local.equals("color") || local.equals("face") || local.equals("size")
          || attrMaySet(a, "color") || attrMaySet(a, "face") || attrMaySet(a, "size")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the output leaves out the tag of element {@code name} with {@code attributes}:
   * {@code <t:block>} and {@code t:remove="tag"}. A browser then parses its children in its parent.
   */
  static boolean omitsTag(String name, List<Attribute> attributes) {
    if (name.equals("t:block")) {
      return true;
    }
    for (Attribute a : attributes) {
      if (a.name().equals("t:remove") && a.value() != null && a.value().trim().equals("tag")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Index in {@code body}, the content of a {@code <script>}, {@code <style>}, {@code <title>} or
   * {@code <textarea>} inside {@code <svg>} or {@code <math>}, of the first {@code <} followed by
   * an ASCII letter, {@code !}, {@code /} or {@code ?}, or -1. A browser parses such content as
   * markup, not as raw text, so the contexts after that {@code <} could differ from Tessera's. A
   * {@code <![CDATA[ ... ]]>} that ends inside {@code body} is skipped: it is text in both readings.
   */
  static int foreignMarkup(String body) {
    for (int i = body.indexOf('<'); i >= 0 && i + 1 < body.length(); i = body.indexOf('<', i + 1)) {
      if (body.startsWith("<![CDATA[", i)) {
        int end = body.indexOf("]]>", i + "<![CDATA[".length());
        if (end < 0) {
          return i;
        }
        i = end;
        continue;
      }
      char c = body.charAt(i + 1);
      if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '!' || c == '/' || c == '?') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Index in {@code body}, the content of a {@code <script>} or {@code <style>} inside {@code <svg>}
   * or {@code <math>} without markup (see {@link #foreignMarkup}), of the first {@code &} outside a
   * {@code <![CDATA[ ... ]]>} section, or -1. There a browser decodes character references before
   * the script or style sees the text, so {@code &quot;} would be a quote that Tessera did not see.
   */
  static int foreignReference(String body) {
    for (int i = 0; i < body.length(); i++) {
      if (body.startsWith("<![CDATA[", i)) {
        int end = body.indexOf("]]>", i + "<![CDATA[".length());
        if (end >= 0) {
          i = end + "]]>".length() - 1;
          continue;
        }
      }
      if (body.charAt(i) == '&') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Index of a {@code >} before the last char of {@code outer}, a CDATA section as the tokenizer
   * read it, or -1. Outside svg and math a browser reads it as a bogus comment that ends at the
   * first {@code >}, so the contexts after an earlier {@code >} would differ from Tessera's. (The
   * verifier accepts such a section in svg and math. A {@code >} in a DOCTYPE or {@code <?...?>},
   * and text a browser reads as a bogus comment, are its parse errors, #442.)
   */
  static int earlyEnd(String outer) {
    int gt = outer.indexOf('>');
    return gt < outer.length() - 1 ? gt : -1;
  }

  /** The build error for a CDATA section when {@link #earlyEnd} finds a {@code >}. */
  static String earlyEndMessage() {
    return "malformed markup: '>' inside a CDATA section is not supported, even in <svg> and <math>: elsewhere a"
        + " browser ends it at the first '>'; write the text without <![CDATA[, with '>' as &gt;";
  }

  /**
   * Index in {@code body}, the content of raw-text element {@code name}, of the first {@code </}
   * followed by a name that equals {@code name} only under Unicode case folding (for {@code script},
   * {@code script} spelled with U+017F) and then whitespace, {@code /}, {@code >} or the end, or -1.
   * It reads as the end tag, but a browser folds ASCII letters only and goes on.
   */
  static int confusableEndTag(String body, String name) {
    for (int i = body.indexOf("</"); i >= 0; i = body.indexOf("</", i + 1)) {
      int next = i + 2 + name.length();
      if (next <= body.length()) {
        String candidate = body.substring(i + 2, next);
        if (candidate.equalsIgnoreCase(name) && !asciiLower(candidate).equals(asciiLower(name))
            && (next == body.length() || " \t\n\f\r/>".indexOf(body.charAt(next)) >= 0)) {
          return i;
        }
      }
    }
    return -1;
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

  /** The error for a start tag, lower-cased, that is not in {@link #SELECT_CONTENT} inside a select. */
  static String notInSelect(String lower) {
    return "<" + lower + "> inside <select> is not supported: browsers with the classic select parser ignore"
        + " most tags there and parse their content as markup; move the <" + lower + "> out of the <select>"
        + " (a <select> holds only <option>, <optgroup>, <hr> and <t:block>)";
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
    /** See {@link HtmlParser#namespace}. */
    final String namespace;
    /**
     * The rendered element the children are parsed in, as a browser sees it: this one, or for
     * a tag that is not rendered ({@link #omitsTag}) the parent's.
     */
    String contextNamespace;
    String contextName;

    Open(String name, SourcePos pos, Open parent) {
      this.name = name;
      this.pos = pos;
      this.contextNamespace = parent == null ? null : parent.contextNamespace;
      this.contextName = parent == null ? null : parent.contextName;
      this.namespace = namespace(contextNamespace, contextName, name);
    }

    /** Called once the attributes are known. */
    void opened() {
      if (omitsTag(name, attributes)) {
        return;
      }
      boolean html = htmlAnnotation(namespace, name, attributes);
      contextNamespace = html ? HTML : namespace;
      contextName = html ? null : name;
    }

    Element toElement(boolean selfClosing, boolean hasEndTag) {
      return new Element(name, attributes, children, selfClosing, hasEndTag, pos, !namespace.equals(HTML));
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

    /**
     * A template comment is dropped: nothing in it is parsed, compiled or written (#132). The text
     * on either side stays two nodes, so positions in the second stay right.
     */
    @Override
    public void templateComment(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      int prototype = content.substring(start, end).indexOf(PROTOTYPE_COMMENT);
      if (prototype >= 0) {
        diagnostics.error(position(start + prototype), PROTOTYPE_COMMENT_MESSAGE);
      }
    }

    @Override
    public void cdata(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      String outer = str(start, end);
      int gt = earlyEnd(outer);
      if (gt >= 0) {
        diagnostics.error(position(start + gt), earlyEndMessage());
      }
      // An expression inside is a TEXT_NOT_IN_TEXT for the verifier, which names the CDATA section (#411).
      children().add(new Text(str(start, end), textContext(), position(start)));
    }

    @Override
    public void declaration(int start, int end) {
      if (raw != null) {
        return;
      }
      flushText();
      children().add(new DocType(str(start, end), position(start)));
    }

    @Override
    public void startTag(int lt, int nameEnd) {
      if (raw != null) {
        return;
      }
      flushText();
      String name = str(lt + 1, nameEnd);
      tag = new Open(name, position(lt), stack.peek());
      if (name.equalsIgnoreCase("plaintext")) {
        diagnostics.error(tag.pos, "<" + name + "> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>");
      }
      if (!ELEMENT_NAME.matcher(name).matches()) {
        diagnostics.error(tag.pos, "malformed markup: invalid element name '" + name
            + "': element names start with a letter and use letters, digits, '-', '_', ':' and '.'");
      }
      // By name only: a classic select parser keeps svg and math there as HTML.
      String lower = name.toLowerCase(Locale.ROOT);
      if (!SELECT_CONTENT.contains(lower) && inSelect()) {
        diagnostics.error(tag.pos, notInSelect(lower));
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
      // A browser reads <script/>, <style/> or <select/> in HTML as a start tag: the verifier's parse
      // error (#442). <textarea/>, <title/> and the other raw text elements are checked where Tessera
      // writes them (TemplateGenerator).
      checkBreakout(tag);
      children().add(tag.toElement(minimized, false));
      tag = null;
    }

    /** Reports a breakout tag ({@link HtmlParser#breaksOut}) inside svg or math. */
    private void checkBreakout(Open open) {
      if (breaksOut(open.namespace, open.name, open.attributes)) {
        diagnostics.error(open.pos, "malformed markup: <" + open.name + "> inside <svg> or <math> is HTML in a"
            + " browser and ends the <svg> or <math> there: close it first, or put HTML inside <foreignObject>");
      }
    }

    @Override
    public void openTagEnd(int gt) {
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
        // Only ASCII letters fold: a browser does not end <script> at </script> spelled with U+017F.
        if (!HtmlParser.asciiLower(raw.name).equals(HtmlParser.asciiLower(name))) {
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
        int templateComment = body.indexOf("<!--/*");
        int confusable = HtmlParser.confusableEndTag(body, raw.name);
        if (confusable >= 0) {
          // It reads as the end tag, but a browser goes on past it. Fail closed.
          diagnostics.error(position(rawStart + confusable), "malformed markup: '"
              + body.substring(confusable, confusable + 2 + raw.name.length()) + "' inside <" + raw.name
              + "> looks like its end tag but a browser doesn't end the element there: remove it");
        }
        boolean foreign = !raw.namespace.equals(HTML);
        int markup = foreign ? foreignMarkup(body) : -1;
        int reference = foreign && markup < 0 && (raw.name.equalsIgnoreCase("script")
            || raw.name.equalsIgnoreCase("style")) ? foreignReference(body) : -1;
        // A CDATA section in an SVG <title> (an integration point) is the verifier's exclusion (d) (#441).
        if (markup >= 0) {
          // Inside svg and math a browser parses this content as markup (SVG <title> as HTML), so
          // a tag in it is a real element that Tessera reads as text. Fail closed.
          diagnostics.error(position(rawStart + markup), "malformed markup: '<' inside <" + raw.name
              + "> in <svg> or <math> starts markup in a browser, not text: write &lt; for a literal '<'");
        } else if (reference >= 0) {
          // There a browser also decodes &quot; and the like before the script or style sees the
          // text, so the JS or CSS state at a hole is not the one Tessera lexed. Title and textarea
          // are TEXT, where both readings decode. Fail closed.
          diagnostics.error(position(rawStart + reference), "malformed markup: '&' inside <" + raw.name
              + "> in <svg> or <math> is decoded by a browser: write the character itself, or use a CDATA section");
        } else if (raw.name.equalsIgnoreCase("script") && comment >= 0) {
          // Browsers apply script-data escape rules after "<!--" ("<script" inside it hides
          // the next "</script>"); the tokenizer does not. Fail closed.
          diagnostics.error(position(rawStart + comment),
              "malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)");
        } else if (templateComment >= 0) {
          // A browser reads it as text here, but Thymeleaf drops it everywhere. Fail closed.
          diagnostics.error(position(rawStart + templateComment), "malformed markup: '<!--/*' inside <"
              + raw.name + "> is text, not a template comment: move the comment outside <" + raw.name + ">");
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
