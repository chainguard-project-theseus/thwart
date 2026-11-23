package dev.chainguard.thwart.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.Diagnostic;
import dev.chainguard.thwart.compiler.Diagnostics;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.TemplateSource;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Comment;
import dev.chainguard.thwart.compiler.model.DocType;
import dev.chainguard.thwart.compiler.model.Document;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.model.Node;
import dev.chainguard.thwart.compiler.model.OutputContext;
import dev.chainguard.thwart.compiler.model.Text;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlParserTest {

  @Test
  void asciiHelpersFoldOnlyAToZ() {
    // equalsIgnoreCase would fold each of these decoys to the ASCII name; a browser does not.
    for (String[] pair :
        new String[][] {{"\u017Fcript", "script"}, {"\u0131frame", "iframe"}, {"\u0130frame", "iframe"},
            {"\u212Abd", "kbd"}}) {
      assertFalse(HtmlParser.asciiEqualsIgnoreCase(pair[0], pair[1]), pair[0]);
      assertFalse(HtmlParser.asciiRegionMatches("</" + pair[0] + ">", 2, pair[1]), pair[0]);
      assertEquals(pair[0], HtmlParser.asciiLower(pair[0]));
    }
    assertTrue(HtmlParser.asciiEqualsIgnoreCase("ScRiPt", "script"));
    assertFalse(HtmlParser.asciiEqualsIgnoreCase("script", null));
    assertTrue(HtmlParser.asciiRegionMatches("</STYLE>", 2, "style"));
    assertFalse(HtmlParser.asciiRegionMatches("</sty", 2, "style"));
    assertFalse(HtmlParser.asciiRegionMatches("x", -1, "x"));
    // The confusable end-tag check still finds each decoy, and only those.
    assertEquals(1, HtmlParser.confusableEndTag("a</\u017Fcript>", "script"));
    assertEquals(1, HtmlParser.confusableEndTag("a</\u0130frame>", "iframe"));
    assertEquals(-1, HtmlParser.confusableEndTag("a</SCRIPT></scripts>", "script"));
  }

  private Diagnostics diagnostics;

  private Document parse(String html) {
    diagnostics = new Diagnostics();
    return new HtmlParser().parse(new TemplateSource("t", "dir/t.html", html), diagnostics);
  }

  private Document parseOk(String html) {
    Document doc = parse(html);
    assertEquals(List.of(), diagnostics.all(), "unexpected diagnostics");
    return doc;
  }

  private List<String> errors(String html) {
    parse(html);
    return diagnostics.all().stream().map(Diagnostic::toString).toList();
  }

  private static SourcePos pos(int line, int col) {
    return new SourcePos("dir/t.html", line, col);
  }

  private static Element el(Node n) {
    return assertInstanceOf(Element.class, n);
  }

  /** Serializes the tree back to HTML (attribute whitespace normalized to one space). */
  static String serialize(List<Node> nodes) {
    StringBuilder sb = new StringBuilder();
    for (Node n : nodes) {
      switch (n) {
        case Text t -> sb.append(t.raw());
        case Comment c -> sb.append(c.raw());
        case DocType d -> sb.append(d.raw());
        case Element e -> {
          sb.append('<').append(e.name());
          for (Attribute a : e.attributes()) {
            sb.append(' ').append(a.name()).append(a.sourceValue());
          }
          sb.append(e.selfClosing() ? "/>" : ">");
          sb.append(serialize(e.children()));
          if (e.hasEndTag()) {
            sb.append("</").append(e.name()).append('>');
          }
        }
        default -> throw new IllegalArgumentException("unknown node " + n);
      }
    }
    return sb.toString();
  }

  @Test
  void roundTripsWellFormedTemplate() {
    String html =
        """
        <!DOCTYPE html>
        <html xmlns:t="https://thwart.chainguard.dev" t:params="OwnerView owner, List<PetView> pets">
        <head>
          <meta charset="utf-8">
          <title>Owner &amp; pets <b>not bold</b></title>
          <link rel="stylesheet" href="/css/app.css">
          <style>body > p { color: red; }</style>
          <script>if (a < b && c > d) { x = "</div>"; }</script>
          <script type="text/x-template"><div a=1 a=1><p></script>
        </head>
        <body class='main' data-x=unquoted hidden>
          <!-- a comment with <b>markup</b> -->
          <h2 t:text="${owner.name}">Name</h2>
          <br><br/><img src="a.png" alt=""/>
          <div t:each="pet : ${pets}" title="a &lt; b &#x41; &unknown; &#65">
            <span t:text="${pet.name}" t:class="x"/>
          </div>
          <textarea name="t"><b>&lt;raw&gt;</b></textarea>
          <svg><text>x &lt; y</text></svg>
          <input type="checkbox" checked disabled="disabled">
          <p class="multi
        line">text</p>
          <t:block t:if="${owner.active}">active</t:block>
          <DIV>Upper</DIV>
        </body>
        </html>
        """;
    Document doc = parseOk(html);
    assertEquals(html, serialize(doc.children()));
  }

  @Test
  void structureOfVoidSelfClosingAndRawElements() {
    Document doc = parseOk("<div><br><br/><span/><script>a</b>c</script></div>");
    Element div = el(doc.rootElement());
    assertEquals(4, div.children().size());
    Element br1 = el(div.children().get(0));
    assertFalse(br1.selfClosing());
    assertFalse(br1.hasEndTag());
    Element br2 = el(div.children().get(1));
    assertTrue(br2.selfClosing());
    Element span = el(div.children().get(2));
    assertTrue(span.selfClosing());
    assertFalse(span.hasEndTag());
    Element script = el(div.children().get(3));
    assertTrue(script.hasEndTag());
    assertEquals(1, script.children().size());
    Text t = assertInstanceOf(Text.class, script.children().get(0));
    assertEquals("a</b>c", t.raw());
    assertEquals(OutputContext.SCRIPT, t.context());
    assertEquals(pos(1, 30), t.pos());
    assertTrue(div.hasEndTag());
  }

  @Test
  void rawTextElementsKeepMarkupAsText() {
    Document doc =
        parseOk(
            "<title>a<b>c</b></title><textarea><i>x</textarea><style>p>q{}</style>"
                + "<script type=\"text/html\"><p a=1 a=1></script>");
    List<Node> top = doc.children();
    assertEquals(List.of("a<b>c</b>", "<i>x", "p>q{}", "<p a=1 a=1>"),
        top.stream().map(n -> ((Text) el(n).children().get(0)).raw()).toList());
    assertEquals(
        List.of(OutputContext.TEXT, OutputContext.TEXT, OutputContext.STYLE, OutputContext.FORBIDDEN),
        top.stream().map(n -> ((Text) el(n).children().get(0)).context()).toList());
    assertEquals(0, el(parseOk("<script src=\"/a.js\"></script>").rootElement()).children().size());
  }

  @Test
  void commentsDoctypeAndProcessingInstructions() {
    Document doc = parseOk("<?xml version=\"1.0\"?><!DOCTYPE html><!-- c --><a>x</a>");
    assertEquals(new DocType("<?xml version=\"1.0\"?>", pos(1, 1)), doc.children().get(0));
    assertEquals(new DocType("<!DOCTYPE html>", pos(1, 22)), doc.children().get(1));
    assertEquals(new Comment("<!-- c -->", pos(1, 37)), doc.children().get(2));
    Element a = el(doc.children().get(3));
    assertEquals(new Text("x", OutputContext.TEXT, pos(1, 50)), a.children().get(0));
  }

  @Test
  void textNodesGetContextAndPosition() {
    Document doc = parseOk("hi\n<p>a &amp; b</p>");
    assertEquals(new Text("hi\n", OutputContext.TEXT, pos(1, 1)), doc.children().get(0));
    Element p = el(doc.children().get(1));
    assertEquals(new Text("a &amp; b", OutputContext.TEXT, pos(2, 4)), p.children().get(0));
  }

  @Test
  void elementAndAttributePositions() {
    String html = "<html>\n  <a  href=\"/x\" t:text='${a}' b=c d\n     e=\"\">x</a>\n</html>";
    Element root = el(parseOk(html).rootElement());
    assertEquals(pos(1, 1), root.pos());
    Element a = el(root.children().get(1));
    assertEquals(pos(2, 3), a.pos());
    List<Attribute> attrs = a.attributes();
    assertEquals(new Attribute("href", "/x", "/x", '"', pos(2, 7), pos(2, 13)), attrs.get(0));
    assertEquals(
        new Attribute("t:text", "${a}", "${a}", '\'', pos(2, 17), pos(2, 25)), attrs.get(1));
    assertEquals(new Attribute("b", "c", "c", (char) 0, pos(2, 31), pos(2, 33)), attrs.get(2));
    assertEquals(new Attribute("d", null, null, (char) 0, pos(2, 35), null), attrs.get(3));
    assertEquals(new Attribute("e", "", "", '"', pos(3, 6), pos(3, 9)), attrs.get(4));
  }

  @Test
  void multiLineAttributeValuePositions() {
    String html = "<div\n  t:text=\"${a}\"\n  title=\"line one\n  line two &amp;\n\" t:if=\"${x}\"></div>";
    Element div = el(parseOk(html).rootElement());
    Attribute title = div.attribute("title").orElseThrow();
    assertEquals(pos(3, 3), title.pos());
    assertEquals(pos(3, 10), title.valuePos());
    assertEquals("line one\n  line two &amp;\n", title.rawValue());
    assertEquals("line one\n  line two &\n", title.value());
    Attribute tIf = div.attribute("t:if").orElseThrow();
    assertEquals(pos(5, 3), tIf.pos());
    assertEquals(pos(5, 9), tIf.valuePos());
    // Offsets inside a raw value map through SourcePos.advance.
    assertEquals(pos(4, 3), title.valuePos().advance(title.rawValue(), title.rawValue().indexOf("line two")));
  }

  @Test
  void positionsAfterEntities() {
    String html = "<p title=\"&amp;&lt;\" t:text=\"${'&lt;' + x}\">&amp; &#x41;<b t:if=\"${y}\"></b></p>";
    Element p = el(parseOk(html).rootElement());
    Attribute text = p.attribute("t:text").orElseThrow();
    assertEquals(pos(1, 22), text.pos());
    assertEquals(pos(1, 30), text.valuePos());
    assertEquals("${'<' + x}", text.value());
    assertEquals("${'&lt;' + x}", text.rawValue());
    // Position of 'x' in the raw value.
    assertEquals(pos(1, 41), text.valuePos().advance(text.rawValue(), text.rawValue().indexOf('x')));
    Element b = el(p.children().get(1));
    assertEquals(pos(1, 57), b.pos());
    assertEquals(pos(1, 60), b.attributes().get(0).pos());
    assertEquals(pos(1, 66), b.attributes().get(0).valuePos());
  }

  @Test
  void decodesCharacterReferences() {
    assertEquals("a & b", HtmlParser.decode("a &amp; b"));
    assertEquals("<>\"' ", HtmlParser.decode("&lt;&gt;&quot;&apos;&nbsp;"));
    assertEquals("AAA", HtmlParser.decode("&#65;&#x41;&#X41;"));
    assertEquals("j", HtmlParser.decode("&#106"));
    assertEquals("😀", HtmlParser.decode("&#x1F600;"));
    // #916: every named reference the standard defines, with its ';'.
    assertEquals("&unknown; \u00a9 &amp & &copy", HtmlParser.decode("&unknown; &copy; &amp & &copy"));
    assertEquals("\u00a9\u00a9:\t/", HtmlParser.decode("&COPY;&copy;&colon;&Tab;&sol;"));
    assertEquals("\u2242\u0338\ud835\udd57", HtmlParser.decode("&NotEqualTilde;&fopf;"));
    assertEquals("&#; &#x; &#xZ;", HtmlParser.decode("&#; &#x; &#xZ;"));
    assertEquals("���", HtmlParser.decode("&#0;&#xD800;&#x110000;"));
    assertEquals("�", HtmlParser.decode("&#99999999999999999999;"));
    assertEquals("&lt;", HtmlParser.decode("&amp;lt;"));
    assertEquals("plain", HtmlParser.decode("plain"));
  }

  @Test
  void attributeValueDecodedRawKept() {
    Element a = el(parseOk("<a title=\"&#106;avascript&colon;\" t:text=\"${a &gt; b}\"></a>").rootElement());
    assertEquals("javascript:", a.attributes().get(0).value());
    assertEquals("&#106;avascript&colon;", a.attributes().get(0).rawValue());
    assertEquals("${a > b}", a.attributes().get(1).value());
  }

  // ---- errors (negative tests) ----

  @Test
  void selfClosingScriptStyleOrSelectIsLeftToTheVerifier() {
    // A browser treats <script/> as an open tag: the verifier's parse error, with the fix-it
    // (#442, verify/ParseErrorFixItTest). Thwart's tree reads these as self-closed.
    assertEquals(List.of(), errors("<script/>[[${x}]]<script>f()</script>"));
    assertEquals(List.of(), errors("<p></p>\n  <SCRIPT src=\"a.js\"/><div><style/><select/></div>"));
    assertEquals(List.of(), errors("<script></script><style></style>"));
    // TemplateGenerator checks <textarea/>, <title/> and the other raw text elements, where it knows
    // whether it writes them with an end tag (<textarea t:text="x"/>).
    assertEquals(List.of(), errors("<svg><title/></svg><textarea/>"));
  }

  /** The error for a CDATA section at column {@code col} of line 1 (#678), after {@code first}. */
  private static List<String> withCdata(int col, String... first) {
    List<String> all = new ArrayList<>(List.of(first));
    all.add("dir/t.html:1:" + col + ": " + CDATA);
    return all;
  }

  private static final String CDATA = "error: a CDATA section is not supported: a browser reads <![CDATA[ by different"
      + " rules inside and outside <svg> and <math>; write the text without <![CDATA[ and ]]>, with '<' as &lt; and '&'"
      + " as &amp;";

  @Test
  void markupABrowserEndsAtItsFirstGreaterThanIsAnError() {
    // #678: any CDATA section is an error at its start (a browser reads it as a bogus comment outside svg and
    // math); in a script or style it is text.
    assertEquals(withCdata(4), errors("<p><![CDATA[ x><script>[[${x}]]</script> ]]></p>"));
    assertEquals(withCdata(6), errors("<svg><![cdata[\n >]]></svg>"));
    assertEquals(List.of(), errors("<script>//<![CDATA[\nif (a > b) {}\n//]]></script>"));
    // A '>' in a <?...?> or DOCTYPE is the verifier's parse error, with the fix-it (#442,
    // verify/ParseErrorFixItTest).
    assertEquals(List.of(), errors("<?php><script>[[${x}]]</script> ?>"));
    assertEquals(List.of(), errors("<?xml version=\"1.0\" encoding=\"><script>\"?>"));
    assertEquals(List.of(), errors("<!DOCTYPE html PUBLIC \"><script>\" \"x\">"));
    // Without an earlier '>', a browser ends them where Thwart does.
    assertEquals(
        List.of(),
        errors("<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"x.dtd\">"
            + "<p><?php echo 1 ?></p>"));
  }

  @Test
  void textABrowserReadsAsABogusCommentIsLeftToTheVerifier() {
    // A browser starts a bogus comment at "</ ", "<?" or "<!" in text that ends at the first '>':
    // the verifier's parse error, at the '<' and with the fix-it (#442, verify/ParseErrorFixItTest).
    assertEquals(List.of(), errors("<p>a </ b <!-- > <script> -->[[${x}]]<!-- </script> --></p>"));
    assertEquals(List.of(), errors("<p>\n  </ x></p>a <? b ?><i>a <!-x> <![if !IE]></i>"));
    // A '<' that starts no tag or comment in a browser stays text.
    assertEquals(List.of(), errors("<p>x < y, a <- b, a <[b], a &lt;!b, a &lt;/ b</p>"));
    // RCDATA and raw text have no comments in a browser.
    assertEquals(
        List.of(),
        errors("<textarea>Type </ html> literally</textarea><title>a <!x> <? b</title>"
            + "<script>if (a </ b) {}</script><style>p::before { content: \"<!x>\" }</style>"));
  }

  @Test
  void duplicateAttributes() {
    assertEquals(
        List.of("dir/t.html:1:16: error: duplicate attribute 'class' on <div>: remove one of them"),
        errors("<div class=\"a\" class=\"b\"></div>"));
    assertEquals(
        List.of("dir/t.html:1:18: error: duplicate attribute 'T:TEXT' on <p>: remove one of them"),
        errors("<p t:text=\"${a}\" T:TEXT=\"${b}\"></p>"));
    assertEquals(
        List.of("dir/t.html:1:17: error: duplicate attribute 'disabled' on <input>: remove one of them"),
        errors("<input disabled disabled>"));
  }

  /**
   * Contexts reads the first script {@code type} and MarkupTokenizer the last; this check, folding
   * A-Z, is what keeps them from disagreeing (#591).
   */
  @Test
  void duplicateScriptTypeIsAnError() {
    assertEquals(
        List.of("dir/t.html:1:23: error: duplicate attribute 'TYPE' on <script>: remove one of them"),
        errors("<script type=\"module\" TYPE=\"text/plain\"></script>"));
    assertEquals(
        List.of("dir/t.html:1:32: error: duplicate attribute 'type' on <script>: remove one of them"),
        errors("<script type=\"text/javascript\" type=\"application/json\"></script>"));
    assertEquals(List.of(), errors("<script type=\"module\"></script>"));
  }

  @Test
  void duplicateAttributesFoldOnlyAscii() {
    // A browser lower-cases A-Z only, so each pair is two attributes: Kelvin sign and k, \u00C9 and \u00E9,
    // dotted capital I and i + combining dot, long s and s.
    assertEquals(List.of(), errors("<track \u212Aind=\"a\" kind=\"b\">"));
    assertEquals(List.of(), errors("<p data-\u00C9=\"a\" data-\u00E9=\"b\"></p>"));
    assertEquals(List.of(), errors("<p \u0130d=\"a\" i\u0307d=\"b\"></p>"));
    assertEquals(List.of(), errors("<p \u017Ftyle=\"a\" style=\"b\"></p>"));
    assertEquals(
        List.of("dir/t.html:1:14: error: duplicate attribute 'style' on <p>: remove one of them"),
        errors("<p STYLE=\"a\" style=\"b\"></p>"));
  }

  @Test
  void duplicateAttributeAcrossLines() {
    assertEquals(
        List.of("dir/t.html:2:3: error: duplicate attribute 'href' on <a>: remove one of them"),
        errors("<a href=\"/x\"\n  href=\"javascript:alert(1)\"/>"));
  }

  @Test
  void unclosedElements() {
    assertEquals(
        List.of("dir/t.html:1:6: error: element <p> is not closed (found </div> at 1:10): add </p>"),
        errors("<div><p>x</div>"));
    assertEquals(
        List.of("dir/t.html:1:1: error: element <div> is not closed: add </div>"), errors("<div><span></span>"));
    assertEquals(
        List.of("dir/t.html:2:1: error: element <script> is not closed: add </script>"),
        errors("<p></p>\n<script>alert(1)"));
  }

  @Test
  void mismatchedElements() {
    assertEquals(
        List.of(
            "dir/t.html:1:4: error: element <i> is not closed (found </b> at 1:7): add </i>",
            "dir/t.html:1:11: error: end tag </i> does not match any open element: remove it or add the matching <i>"),
        errors("<b><i></b></i>"));
    assertEquals(
        List.of("dir/t.html:1:13: error: end tag </span> does not match any open element: remove it or add the matching <span>"),
        errors("<div>x</div></span>"));
    assertEquals(
        List.of("dir/t.html:1:5: error: end tag </br> does not match any open element: remove it or add the matching <br>"),
        errors("<br></br>"));
  }

  @Test
  void endTagsMatchFoldingAsciiLettersOnly() {
    // A browser lowercases A-Z only, so these close their elements, SVG camelCase names included.
    Document doc = parseOk("<SPAN>a</span><Div></DIV><t:block></T:Block>"
        + "<svg><foreignObject></foreignobject><linearGradient></LINEARGRADIENT></svg>");
    assertEquals("<SPAN>a</SPAN><Div></Div><t:block></t:block>"
        + "<svg><foreignObject></foreignObject><linearGradient></linearGradient></svg>", serialize(doc.children()));
  }

  private static String invalidEndTagName(int col, String name) {
    return "dir/t.html:1:" + col + ": error: malformed markup: invalid element name '" + name + "' in end tag </"
        + name + ">: element names start with a letter and use letters, digits, '-', '_', ':' and '.'";
  }

  @Test
  void nonAsciiEndTagNamesAreErrors() {
    // Each name equals the open element's only under Unicode case folding (U+017F long s, U+0131
    // dotless i, U+212A Kelvin sign). A browser does not read it as that element's end tag, so the
    // element would stay open there, while Thwart would close it and write the end tag itself.
    assertEquals(
        List.of(invalidEndTagName(8, "\u017Fpan"), "dir/t.html:1:1: error: element <span> is not closed: add </span>"),
        errors("<span>a</\u017Fpan><p>b</p>"));
    assertEquals(
        List.of(invalidEndTagName(10, "\u0131frame"),
            "dir/t.html:1:1: error: element <iframe> is not closed: add </iframe>"),
        errors("<iframe>a</\u0131frame>"));
    assertEquals(
        List.of(invalidEndTagName(12, "no\u017Fcript"),
            "dir/t.html:1:1: error: element <noscript> is not closed: add </noscript>"),
        errors("<noscript>a</no\u017Fcript>"));
    assertEquals(
        List.of(invalidEndTagName(27, "\u017Felect"),
            "dir/t.html:1:1: error: element <select> is not closed: add </select>"),
        errors("<select><option>a</option></\u017Felect>"));
    assertEquals(
        List.of(invalidEndTagName(7, "\u212Abd"), "dir/t.html:1:1: error: element <kbd> is not closed: add </kbd>"),
        errors("<kbd>a</\u212Abd>"));
    // ASCII names outside the start-tag grammar too
    assertEquals(
        List.of(invalidEndTagName(7, "d!v"), "dir/t.html:1:1: error: element <div> is not closed: add </div>"),
        errors("<div>a</d!v>"));
  }

  @Test
  void malformedMarkup() {
    assertEquals(
        List.of("dir/t.html:1:6: error: malformed markup: invalid attribute name '<p': attribute names cannot contain <, quotes or ="),
        errors("<div <p></div>"));
    assertEquals(
        List.of("dir/t.html:1:1: error: malformed markup: invalid element name '<': element names start with a letter and use letters, digits, '-', '_', ':' and '.'"), errors("<</>"));
    List<String> unterminated = errors("<p>ok</p>\n<div class=\"x>hi</div>");
    assertEquals(1, unterminated.size());
    assertTrue(unterminated.get(0).startsWith("dir/t.html:2:1: error: malformed markup: "), unterminated.get(0));
    List<String> comment = errors("<p>x</p><!-- never closed");
    assertEquals(1, comment.size());
    assertTrue(comment.get(0).startsWith("dir/t.html:1:9: error: malformed markup: "), comment.get(0));
    assertEquals(1, errors("<a href=x\"y></a>").size());
  }

  @Test
  void quoteOutsideQuotedValueIsAnError() {
    // A browser opens a quoted value only at a quote right after '=' (and whitespace); anywhere
    // else a quote is a plain character, so the tag ends at the first '>' while the tokenizer
    // would skip it. In the first case a browser opens the <script> and runs the text after it.
    String dq = "error: malformed markup: quote \" in an unquoted attribute value: quote the value and write &quot; in it";
    String sq = "error: malformed markup: quote ' in an unquoted attribute value: quote the value and write &#39; in it";
    String dqName = "error: malformed markup: quote \" in a tag or attribute name: remove it (names cannot contain quotes)";
    assertEquals(List.of("dir/t.html:1:11: " + dq), errors("<p title=a\"><script>//\">[[${x}]]//</p><!-- </script> -->"));
    assertEquals(List.of("dir/t.html:2:13: " + sq), errors("<p>\n  <p title=a'b'></p></p>"));
    assertEquals(List.of("dir/t.html:1:10: " + dqName), errors("<a b=\"x\"y\"><script>\"></a>"));
    assertEquals(List.of("dir/t.html:1:9: " + dqName), errors("<a b=\"x\"\"y\"></a>"));
    assertEquals(List.of("dir/t.html:1:8: " + dqName), errors("<p></p \"><script>//\"></p>"));
    assertEquals(List.of("dir/t.html:1:11: " + sq), errors("<p></p a=b'>'>"));
    assertEquals(List.of("dir/t.html:1:3: " + dqName), errors("<p\"x\"></p>"));
    assertEquals(List.of("dir/t.html:1:4: " + dqName), errors("<a \"b\"></a>"));
    assertEquals(List.of("dir/t.html:1:5: " + dqName), errors("<a b\"c=d></a>"));
    String eq = "error: malformed markup: '=' starting an unquoted value is part of it in a browser: quote the value";
    assertEquals(List.of("dir/t.html:1:6: " + eq), errors("<a b==\"x>\"></a>"));
    assertEquals(List.of("dir/t.html:1:7: " + eq), errors("<a b= =\"x>\"></a>"));
    assertEquals(List.of("dir/t.html:1:6: error: malformed markup: a name after '/' in a tag starts a new attribute in a browser:"
        + " remove the '/'"), errors("<a b/=\"x>\"></a>"));
    assertEquals(List.of("dir/t.html:1:4: " + dqName), errors("<a/\"b\"></a>"));
    assertEquals(List.of("dir/t.html:1:7: " + sq), errors("<a b=c'd e='f'></a>"));
    assertEquals(List.of("dir/t.html:1:5: error: malformed markup: quote ' in a tag or attribute name: remove it (names cannot"
        + " contain quotes)"), errors("<a b'c></a>"));
  }

  @Test
  void quotedValuesNextToQuotesStillParse() {
    parseOk("<a b = \"x\" c='y\"z' d=\"it's\" e=\n'f'></a>");
    parseOk("<a b=\"x\"c='y'd=e></a>");
    parseOk("<a b='>' c=\"<\" d=\"/>\"></a>");
    parseOk("<br b=\"x\"/><br c='y' />");
    parseOk("<input value=it&#39;s><input value=&quot;q&quot;>");
    parseOk("<p>it's \"quoted\"</p ><p>'</p\n>");
    parseOk("<script>var a = \"<b c=d'>\";</script>");
    // raw text a browser does not tokenize keeps the old quote rule
    parseOk("<script type=\"text/template\"><% if (a === 'b') { %>x<% } %><p title=a'b'></p></script>");
    parseOk("<textarea><a b=c'd'></a></textarea><title>x<y \"z\"></title>");
    parseOk("<div t:params=\"String s\"><a t:title=\"'x' + s\" t:text=\"${s == 'y' ? 'z' : s}\"></a></div>");
  }

  @Test
  void javaOnlyWhitespaceInTagIsAnError() {
    // Character.isWhitespace accepts these, HTML whitespace is only \t \n \f \r and space: a
    // browser reads <textarea> followed by U+000B as an unknown element, so its content is live markup.
    String msg = "error: malformed markup: U+%04X in a tag is not HTML whitespace: replace it with a space";
    assertEquals(List.of("dir/t.html:1:10: " + msg.formatted(0x0B)),
        errors("<textarea\u000b><a onclick=\"[[${x}]]\">x</a></textarea>"));
    assertEquals(List.of("dir/t.html:1:7: " + msg.formatted(0x0B)),
        errors("<title\u000b><img src=x onerror=\"[[${x}]]\"></title>"));
    assertEquals(List.of("dir/t.html:1:8: " + msg.formatted(0x0B)), errors("<script\u000b>var a = [[${x}]];</script>"));
    assertEquals(List.of("dir/t.html:1:8: " + msg.formatted(0x0B)), errors("<p>x</p\u000b>"));
    assertEquals(List.of("dir/t.html:1:17: " + msg.formatted(0x0B)),
        errors("<script data-a=b\u000btype=text/template>[[${x}]]</script>"));
    assertEquals(List.of("dir/t.html:1:3: " + msg.formatted(0x1C)), errors("<a\u001cb=c></a>"));
    assertEquals(List.of("dir/t.html:1:13: " + msg.formatted(0x2028)), errors("<p title=\"x\"\u2028t:text=\"s\"></p>"));
    assertEquals(List.of("dir/t.html:1:10: " + msg.formatted(0x3000)), errors("<textarea\u3000>x</textarea>"));
    assertEquals(List.of("dir/t.html:1:6: " + msg.formatted(0x2003)), errors("<a b=\u2003c></a>"));
  }

  @Test
  void htmlWhitespaceAndNonAsciiOutsideTagsStillParse() {
    parseOk("<a\tb=c\nd=e\fg=h\ri=j  k=l\r\n></a\n>");
    parseOk("<p title=\"a\u000bb\u2028c\u3000d\" lang='\u001c'>\u000b\u2028\u3000\u001cx</p>");
    parseOk("<textarea>a\u000b</textarea><title>\u3000</title><script>var a = '\u2028';</script>");
    // raw text a browser does not tokenize is exempt
    parseOk("<script type=\"text/template\"><p\u000bx=y></p></script>");
  }

  @Test
  void slashOutsideSelfClosingIsAnError() {
    // A '/' in an unquoted value belongs to the value, so <textarea x=y/> is an open tag in a browser.
    String value = "error: malformed markup: '/' before '>' belongs to the unquoted value, not to '/>': quote the value";
    assertEquals(List.of("dir/t.html:1:14: " + value), errors("<textarea x=y/><a title=\"</textarea>\">x</a>"));
    assertEquals(List.of("dir/t.html:1:6: " + value), errors("<a b=/>"));
    assertEquals(List.of("dir/t.html:1:11: " + value), errors("<img src=a/>"));
    // A browser starts a new attribute right after '/', where the tokenizer keeps the '/' in the name.
    String name = "error: malformed markup: a name after '/' in a tag starts a new attribute in a browser: remove the '/'";
    assertEquals(List.of("dir/t.html:1:14: " + name), errors("<p title=\"x\"/t:text=\"s\">a</p>"));
    assertEquals(List.of("dir/t.html:1:6: " + name), errors("<img/src=x>"));
    assertEquals(List.of("dir/t.html:1:4: " + name), errors("<a/b></a>"));
    // Whitespace after the '/' is an error of its own (see slashMustBeFollowedByGt). With a name
    // after it, a browser reads a valueless type, then ignores the duplicate, so this is a classic
    // script while Thwart reads a JSON one.
    String slash = "error: malformed markup: '/' in a tag must be followed by '>': remove the '/' or what follows it";
    assertEquals(List.of("dir/t.html:1:14: " + slash),
        errors("<script type/ type=\"application/json\">[[${x}]]</script>"));
    assertEquals(List.of("dir/t.html:1:6: " + slash), errors("<a b/ c></a>"));
    assertEquals(List.of("dir/t.html:1:11: " + slash), errors("<a b=\"c\" /\nd></a>"));
    assertEquals(List.of("dir/t.html:1:8: " + slash), errors("<p></p/ x>"));
    parseOk("<br/><br /><br b=c /><br b=\"c\"/><img src=\"a/\"><a href=/x/ >y</a>");
  }

  @Test
  void slashMustBeFollowedByGt() {
    // The tokenizer reads "/ >" as an attribute named '/' that a browser does not have (13.2.5.40
    // reconsumes the space in the before attribute name state). A rebuilt tag renders it as " />",
    // which closes an svg element, so what follows is not in the element Thwart escaped it for.
    String slash = "error: malformed markup: '/' in a tag must be followed by '>': remove the '/' or what follows it";
    assertEquals(List.of("dir/t.html:1:27: " + slash), errors("<svg><g t:if=\"${true}\" x / >[[${x}]]</g></svg>"));
    assertEquals(List.of("dir/t.html:1:30: " + slash),
        errors("<svg><script t:if=\"${true}\" / >var a = [[${x}]];</script></svg>"));
    assertEquals(List.of("dir/t.html:1:6: " + slash), errors("<br / >"));
    assertEquals(List.of("dir/t.html:1:5: " + slash), errors("<br//>"));
    assertEquals(List.of("dir/t.html:1:6: " + slash), errors("<br //>"));
    assertEquals(List.of("dir/t.html:1:6: " + slash), errors("<br\t/\n>"));
    parseOk("<br/><br /><br\t/><svg><g x=\"y\"/><g x />t</svg>");
  }

  @Test
  void equalsStartingAnUnquotedValueIsAnError() {
    // A browser keeps the second '=' in the value (13.2.5.35 reconsumes it in the unquoted value
    // state); the tokenizer skips the run of '=', so a rebuilt tag drops it from the value.
    String eq = "error: malformed markup: '=' starting an unquoted value is part of it in a browser: quote the value";
    assertEquals(List.of("dir/t.html:1:24: " + eq), errors("<a t:if=\"${true}\" href==javascript:alert(1)>x</a>"));
    assertEquals(List.of("dir/t.html:1:11: " + eq), errors("<a title= =b>x</a>"));
    parseOk("<a href=\"=x\" title='=' b = c d=e=f>x</a>");
  }

  @Test
  void malformedMarkupStillReturnsPartialDocument() {
    Document doc = parse("<p>ok</p><div class=\"x>");
    assertTrue(diagnostics.hasErrors());
    assertEquals("p", el(doc.rootElement()).name());
  }

  @Test
  void caseInsensitiveEndTags() {
    Element div = el(parseOk("<DIV><TextArea>x</TEXTAREA><SCRIPT>y</SCRIPT></div>").rootElement());
    assertEquals("DIV", div.name());
    assertEquals("TextArea", el(div.children().get(0)).name());
    assertEquals("SCRIPT", el(div.children().get(1)).name());
  }

  @Test
  void rawTextEndTagMatchesCaseInsensitively() {
    // Browsers end <Script> at </sCrIpT>; what follows is markup again.
    Element div =
        el(parseOk("<div><Script>a</b></sCrIpT><STYLE>p{}</style><p>[[${x}]]</p></div>").rootElement());
    assertEquals(3, div.children().size());
    Element script = el(div.children().get(0));
    assertEquals("Script", script.name());
    assertTrue(script.hasEndTag());
    Text js = assertInstanceOf(Text.class, script.children().get(0));
    assertEquals("a</b>", js.raw());
    assertEquals(OutputContext.SCRIPT, js.context());
    assertEquals(pos(1, 14), js.pos());
    Text css = assertInstanceOf(Text.class, el(div.children().get(1)).children().get(0));
    assertEquals("p{}", css.raw());
    assertEquals(OutputContext.STYLE, css.context());
    Element p = el(div.children().get(2));
    assertEquals(new Text("[[${x}]]", OutputContext.TEXT, pos(1, 49)), p.children().get(0));
    assertEquals("x", ((Text) el(parseOk("<script>x</SCRIPT>").rootElement()).children().get(0)).raw());
  }

  @Test
  void rawTextEndTagInAnyCaseCannotBeSkipped() {
    // The first end tag a browser sees ends the element, whatever its case, even when a later
    // one matches the start tag's case exactly.
    assertEquals(
        List.of("dir/t.html:1:1: error: element <script> is not closed: add </script>"),
        errors("<script>x</SCRIPT x><p>[[${x}]]</p>"));
    assertEquals(
        List.of("dir/t.html:1:10: error: malformed markup: content of <script> contains"
            + " '</script' before its end tag: escape it (in a script string, write '<\\/script')"),
        errors("<script>x</SCRIPT x><p>[[${x}]]</p></script>"));
  }

  @Test
  void rawTextEndTagHiddenInAttributeOrCommentIsAnError() {
    // A browser ends <textarea>/<title> at the first </textarea>, even inside what looks like
    // an attribute value or comment, so the <script> below would be live script.
    assertEquals(
        List.of("dir/t.html:1:21: error: malformed markup: content of <textarea> contains"
            + " '</textarea' before its end tag: escape it (in a script string, write '<\\/textarea')"),
        errors("<textarea><a title=\"</textarea><script>[[${x}]]</script>\"></a></textarea>"));
    assertEquals(
        List.of("dir/t.html:2:5: error: malformed markup: content of <title> contains"
            + " '</title' before its end tag: escape it (in a script string, write '<\\/title')"),
        errors("<title>\n<!--</title ><script>[[${x}]]</script>--></title>"));
    // A script's content is raw text whatever its type, as in a browser: it ends at the first </script>.
    Element script = el(parseOk("<script type=\"text/html\"><a b=\"</script>").children().get(0));
    assertEquals("<a b=\"", ((Text) script.children().get(0)).raw());
    // A DOCTYPE or <?xml there is text too, so its quotes hide nothing.
    String title = "dir/t.html:1:21: error: malformed markup: content of <title> contains '</title' before its end"
        + " tag: escape it (in a script string, write '<\\/title')";
    assertEquals(List.of(title), errors("<title><!DOCTYPE x \"</title><script>alert(1)</script>\"></title>"));
    assertEquals(List.of(title.replace("1:21", "1:15")), errors("<title><?xml \"</title><script>alert(1)</script>?></title>"));
    parseOk("<textarea></textareax></textarea>");
  }

  /**
   * The parser never throws: any input, including input cut off in every tokenizer state, gives a
   * document and diagnostics that name the file and a line. Kept from the attoparser differential
   * (#325), which also caught crashes; the inputs are its edge cases and seeded soups of its pieces.
   */
  @Test
  void anyInputParsesWithoutThrowing() {
    List<String> inputs = new ArrayList<>(List.of(
        "", "<", "<a", "<a ", "<a b", "<a b=", "<a b='", "<a b='c", "<a b='c'", "</", "</a", "</a ", "<!", "<!-",
        "<!--", "<!-- x", "<!-- x --", "<![CDATA[", "<![CDATA[x]", "<![CDATA[x]]", "<!DOCTYPE", "<!DOCTYPE html",
        "<!DOCTYPE html [", "<?", "<?x", "<?xml", "<?xml ", "<?xml version='1'?", "<script>", "<script>x</script",
        "<style>a</STYLE>", "<title>a</title", "<p>a\r\nb</p>", "<div a=1 a=2 A=3>", "<a b=\"x\"=y>", "<a =x>",
        "</a b>", "</a\n>", "</>", "<>", "<!---->", "<!--->-->", "<![CDATA[]]>", "<?ab?>", "<?a?>?>",
        "<noscript></noſcript><title</noscript></title>", "<svg><script/>", "<math><p>", "<xmp><i title=\"</xmp>"));
    String[] pieces = {
      "<", ">", "/", "!", "-", "?", "[", "]", "\"", "'", "=", " ", "\t", "\n", "\r", " ", "\u000B", "&", "a",
      "x", "script", "SCRIPT", "style", "textarea", "title", "xmp", "noscript", "svg", "math", "br", "DOCTYPE",
      "CDATA", "xml", "t:text", "[[${x}]]", "ſ",
    };
    java.util.Random random = new java.util.Random(325);
    for (int i = 0; i < 20_000; i++) {
      StringBuilder sb = new StringBuilder();
      for (int n = 1 + random.nextInt(40); n > 0; n--) {
        sb.append(pieces[random.nextInt(pieces.length)]);
      }
      inputs.add(sb.toString());
    }
    for (String input : inputs) {
      try {
        parse(input);
      } catch (RuntimeException e) {
        throw new AssertionError("HtmlParser threw on " + input, e);
      }
      for (Diagnostic d : diagnostics.all()) {
        assertTrue(d.toString().startsWith("dir/t.html:") && d.pos().line() >= 1, input + " -> " + d);
      }
    }
  }

  private static String hiddenEndTag(int line, int col, String tag) {
    return "dir/t.html:" + line + ":" + col + ": error: malformed markup: content of <" + tag + "> contains '</"
        + tag + "' before its end tag: a browser ends the element there, so the markup after it would be read"
        + " differently; remove it or move it outside the element";
  }

  @Test
  void browserRawTextEndTagMustBeTheElementsOwn() {
    // A browser reads <iframe> and <noscript> (scripting on) as raw
    // text up to the first </name; Thwart parses markup there, so that must be the element's end tag.
    assertEquals(
        List.of(hiddenEndTag(2, 6, "NoScript")), errors("<NoScript>\n<!-- </NOSCRIPT><script> --></noscript>"));
    assertEquals(List.of(hiddenEndTag(1, 21, "noscript")), errors("<noscript><noscript></noscript></noscript>"));
    assertEquals(
        List.of(hiddenEndTag(1, 26, "iframe")), errors("<iframe><script>var a = \"</iframe>\";</script></iframe>"));
    parseOk("<noscript><img src=\"a.png\"></noscript><iframe src=\"/x\"></iframe>");
    parseOk("<div><noscript><p>x</p></NOSCRIPT></div><noscript><noembed></noembed></noscript>");
  }

  @Test
  void browserRawElementsEndWhereABrowserDoes() {
    // #283: a browser reads these as raw text up to the first </name, so an end tag hidden in a
    // nested raw-text element, a comment or an attribute value ends them there.
    for (String tag : List.of("iframe", "noscript")) {
      for (String html : List.of(
          "<" + tag + "><textarea></" + tag + "><script>[[${x}]]</script></textarea></" + tag + ">",
          "<" + tag + "><title></" + tag + "><script>[[${x}]]</script></title></" + tag + ">",
          "<" + tag + "><!--</" + tag + "><script>[[${x}]]</script>--></" + tag + ">",
          "<" + tag + "><p title=\"</" + tag + "><script>/*\">*/[[${x}]]//</p></" + tag + ">")) {
        assertEquals(List.of(hiddenEndTag(1, html.indexOf("</" + tag) + 1, tag)), errors(html), html);
      }
    }
    assertEquals(List.of(hiddenEndTag(2, 5, "iframe")), errors("<iframe>\n<!--</IFRAME >--></iframe>"));
    // unquoted attribute value, and '/' after the name
    assertEquals(List.of(hiddenEndTag(1, 18, "iframe")), errors("<iframe><p title=</iframe>x</p></iframe>"));
    assertEquals(List.of(hiddenEndTag(1, 13, "iframe")), errors("<iframe><!--</iframe/>--></iframe>"));
    // browsers fold ASCII letters only: </no\u017Fcript> is no end tag to them (and an error here), so
    // the </noscript> in the title is where a browser ends the element
    assertEquals(
        List.of("dir/t.html:1:11: error: malformed markup: invalid element name 'no\u017Fcript' in end tag"
                + " </no\u017Fcript>: element names start with a letter and use letters, digits, '-', '_', ':' and '.'",
            "dir/t.html:1:1: error: element <noscript> is not closed: add </noscript>"),
        errors("<noscript></no\u017Fcript><title></noscript></title>"));

    Element noscript = el(parseOk("<noscript><img t:src=\"@{/p.gif}\"><p>[[${x}]]</p></noscript>").children().get(0));
    assertEquals("p", el(noscript.children().get(1)).name());
    parseOk("<iframe>[[${x}]]</iframe>");
    // not an end tag: the name must be followed by whitespace, '/' or '>'
    parseOk("<noscript><a title=\"</noscriptx>\"></a></noscript>");
  }


  @Test
  void plaintextIsAnError() {
    assertEquals(
        List.of("dir/t.html:1:4: error: <PlainText> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>"),
        errors("<p><PlainText>x</PlainText></p>"));
  }

  @Test
  void commentInsideScriptIsAnError() {
    assertEquals(
        List.of(
            "dir/t.html:1:9: error: malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)",
            "dir/t.html:1:48: error: end tag </script> does not match any open element: remove it or add the matching <script>"),
        errors("<script><!--<script></script><p>[[${x}]]</p>--></script>"));
  }

  @Test
  void commentThatEndsEarlierInABrowserIsAnError() {
    String start = "dir/t.html:1:4: error: malformed markup: a comment cannot start with '<!-->' or '<!--->'"
        + " (a browser ends it there): put a space after '<!--'";
    assertEquals(List.of(start), errors("<p><!--> <script> --></p>"));
    assertEquals(List.of(start), errors("<p><!---> <script> --></p>"));
    assertEquals(
        List.of("dir/t.html:2:3: error: malformed markup: '--!>' ends a comment in a browser: end the comment with"
            + " '-->' or remove the '!'"),
        errors("<p><!-- x\nx --!> <script> --></p>"));
    assertEquals(1, errors("<!----!> -->").size());
    assertEquals(1, errors("<!-- --!>-->").size());
  }

  @Test
  void commentsThatEndWhereABrowserEndsThemParse() {
    parseOk("<p><!----><!-- --><!-- -- --><!-- - -><!-- a --!--><!-- !> --></p>");
    // "--!>" ends a comment only when its dashes follow "<!--"
    parseOk("<!--!> --><!---!> -->");
    // Thymeleaf parser-level comments are dropped (templateCommentsAreDropped)
    parseOk("<!--/* hidden */--><!--/**/-->");
    // inside raw text a comment is text to a browser
    parseOk("<textarea><!--> --></textarea><title><!-- --!> --></title>");
  }

  @Test
  void templateCommentsAreDropped() {
    // Thymeleaf's parser-level comment: multi-line, around markup and a "-->", ends at the first "*/-->"
    Document doc = parseOk("<p>a<!--/* x */-->b</p>\n<!--/*\n<div t:text=\"${x}\"><!-- c --> [[${y}]]\n*/-->"
        + "<!--/**/--><!-- /* not a template comment */ --><i>[[${z}]]</i>");
    assertEquals("<p>ab</p>\n<!-- /* not a template comment */ --><i>[[${z}]]</i>", serialize(doc.children()));
    Element p = el(doc.children().get(0));
    assertEquals(List.of(new Text("a", OutputContext.TEXT, pos(1, 4)), new Text("b", OutputContext.TEXT, pos(1, 19))),
        p.children());
    // Nothing inside is parsed: unbalanced markup and a "--!>" are fine.
    parseOk("<!--/* <div> </p> <!--> --!> <script> */-->");
    assertEquals("x", serialize(parseOk("<!--/* a */-->x<!--/* b */-->").children()));
  }

  @Test
  void unclosedTemplateCommentIsAnError() {
    assertEquals(
        List.of("dir/t.html:2:19: error: malformed markup: '<!--/*' starts a template comment that is not closed:"
            + " end it with '*/-->'"),
        errors("<p>\n<p><!--/* x */--> <!--/* y */ --> z</p>"));
  }

  @Test
  void prototypeOnlyCommentIsAnError() {
    String message = "error: Thymeleaf prototype-only comments '<!--/*/ ... /*/-->' are not supported: remove the"
        + " '<!--/*/' and '/*/-->' markers to make the content part of the template";
    assertEquals(List.of("dir/t.html:1:4: " + message), errors("<p><!--/*/ <b t:text=\"${x}\">y</b> /*/--></p>"));
    // Thymeleaf removes its markers inside a parser-level comment too.
    assertEquals(List.of("dir/t.html:1:13: " + message), errors("<!--/* a <b><!--/*/ c /*/--> d */-->"));
  }

  @Test
  void templateCommentInRawTextIsAnError() {
    // A browser reads it as text there, but Thymeleaf would drop it.
    assertEquals(
        List.of("dir/t.html:1:8: error: malformed markup: '<!--/*' inside <style> is text, not a template comment:"
            + " move the comment outside <style>"),
        errors("<style><!--/* a */--> p {}</style>"));
    assertEquals(
        List.of("dir/t.html:1:11: error: malformed markup: '<!--/*' inside <textarea> is text, not a template"
            + " comment: move the comment outside <textarea>"),
        errors("<textarea><!--/* a */--></textarea>"));
    assertEquals(
        List.of("dir/t.html:1:9: error: malformed markup: '<!--' inside <script> is not supported: remove it (it is"
            + " not needed in HTML5)"),
        errors("<script><!--/* a */--></script>"));
    // Elsewhere in a script or style, "/*" is a comment in JavaScript and CSS.
    parseOk("<script>/* a */</script><style>/* b */</style>");
  }

  @Test
  void foreignContentIsByTag() {
    Element svg = el(parseOk("<svg><title>a &lt; b [[${x}]]</title><desc>d</desc><style>.a > b { fill: red }</style>"
        + "<script>if (a > b) { f(\"b\"); }</script><textarea>a < b</textarea><title/></svg>")
        .rootElement());
    assertTrue(svg.foreign());
    List<Element> kids = svg.children().stream().map(HtmlParserTest::el).toList();
    assertTrue(kids.stream().allMatch(Element::foreign));
    // Foreign <title> and <textarea> are TEXT, so {{ is broken there (#277).
    assertEquals(
        List.of(OutputContext.TEXT, OutputContext.TEXT, OutputContext.STYLE, OutputContext.SCRIPT, OutputContext.TEXT),
        kids.subList(0, 5).stream().map(e -> ((Text) e.children().get(0)).context()).toList());

    // Foreign content is by tag (#759): integration points are not modelled, so their children stay
    // foreign, where nothing is computed. A browser's reading of them is the verifier's.
    Element fo = el(el(parseOk("<svg><foreignObject><div><title><b>t</b></title><textarea><i></i></textarea>"
        + "<style>a>b{}</style><script>if (a<b) {}</script></div></foreignObject></svg>").rootElement())
        .children().get(0));
    assertTrue(fo.foreign());
    Element div = el(fo.children().get(0));
    assertTrue(div.foreign());
    Element mi = el(el(el(parseOk("<math><annotation-xml encoding=\"text/html\"><p>x</p></annotation-xml></math>")
        .rootElement()).children().get(0)).children().get(0));
    assertTrue(mi.foreign());
    // Markup a browser would read differently there is the verifier's parse error, not the parser's.
    parseOk("<svg><p>x</p><style><b></style><font color=\"red\">f</font></svg><math><title><i>t</i></title></math>");
    assertFalse(el(parseOk("<svg></svg><div></div>").children().get(1)).foreign());
    assertEquals(
        List.of(OutputContext.TEXT, OutputContext.TEXT, OutputContext.STYLE, OutputContext.SCRIPT),
        div.children().stream().map(n -> ((Text) el(n).children().get(0)).context()).toList());
    parseOk("<math><mtext><textarea><b>x</b></textarea></mtext><mi><title><i>t</i></title></mi></math>");
    parseOk("<svg><desc><b>x</b></desc></svg>");
    parseOk("<svg><t:block><foreignObject t:remove=\"all\"><t:block><title><b>t</b></title></t:block>"
        + "</foreignObject></t:block></svg>");
    parseOk("<math><annotation-xml><svg><title>t</title></svg></annotation-xml></math><title><b>html</b></title>");
  }

  /** Every text node, depth-first, as {@code raw=context}. */
  private static List<String> texts(List<Node> nodes) {
    List<String> out = new ArrayList<>();
    for (Node n : nodes) {
      if (n instanceof Text t) {
        out.add(t.raw() + "=" + t.context());
      } else if (n instanceof Element e) {
        out.addAll(texts(e.children()));
      }
    }
    return out;
  }

  @Test
  void htmlTitleAndTextareaInsideForeignContentStayRcdata() {
    // In an integration point (or outside the svg) these are HTML elements: raw text in Thwart's
    // tree, RCDATA for the verifier, which names their escaper (#378, ContextVerifierRulesTest).
    for (String html :
        List.of(
            "<svg><foreignObject><textarea><i>x</textarea></foreignObject></svg>",
            "<svg><FOREIGNOBJECT><textarea><i>x</textarea></FOREIGNOBJECT></svg>",
            "<svg><desc><title><i>x</title></desc></svg>",
            "<math><mi><textarea><i>x</textarea></mi></math>",
            "<math><mo><textarea><i>x</textarea></mo></math>",
            "<math><mn><textarea><i>x</textarea></mn></math>",
            "<math><ms><textarea><i>x</textarea></ms></math>",
            "<math><mtext><textarea><i>x</textarea></mtext></math>",
            "<math><annotation-xml encoding=\"text/html\"><title><i>x</title></annotation-xml></math>",
            "<math><annotation-xml encoding=\"Application/XHTML+XML\"><title><i>x</title></annotation-xml></math>",
            "<svg></svg><textarea><i>x</textarea>",
            "<svg><foreignObject><p></p><textarea><i>x</textarea></foreignObject></svg>",
            // A tag that is not rendered is not a parent: the browser sees an HTML title or textarea.
            // (RemoveProcessor then rejects t:remove="tag" inside svg and math, #300.)
            "<svg t:remove=\"tag\"><title><i>x</title></svg>",
            "<math><mi><mglyph t:remove=\"tag\"><textarea><i>x</textarea></mglyph></mi></math>")) {
      assertEquals(List.of("<i>x=TEXT"), texts(parseOk(html).children()), html);
    }
    parseOk("<math><annotation-xml t:remove=\"tag\"><svg></svg></annotation-xml></math>");
    parseOk("<math><mi><t:block><mglyph></mglyph></t:block></mi></math>");
    parseOk("<svg><t:block><g></g></t:block></svg>");
  }

  @Test
  void hiddenEndTagInHtmlRawTextInsideForeignContentIsAnError() {
    assertEquals(
        List.of("dir/t.html:1:41: error: malformed markup: content of <textarea> contains"
            + " '</textarea' before its end tag: escape it (in a script string, write '<\\/textarea')"),
        errors("<svg><foreignObject><textarea><a title=\"</textarea><script>\">[[${x}]]</a></textarea>"
            + "</foreignObject></svg>"));
    assertEquals(
        List.of("dir/t.html:1:25: error: malformed markup: content of <title> contains"
            + " '</title' before its end tag: escape it (in a script string, write '<\\/title')"),
        errors("<svg><desc><title><a b=\"</title><script>\">[[${x}]]</a></title></desc></svg>"));
  }

  @Test
  void rawTextEndsOnlyAtAnAsciiCaseInsensitiveEndTag() {
    // Java's equalsIgnoreCase folds U+017F to 's'; a browser does not, so the script goes on there.
    // Such a look-alike end tag is an error.
    String confusable = "dir/t.html:1:10: error: malformed markup: '</\u017Fcript' inside <script> looks like its"
        + " end tag but a browser doesn't end the element there: remove it";
    assertEquals(List.of(confusable), errors("<script>a</\u017Fcript>[[${x}]]</script>"));
    assertEquals(List.of(confusable, "dir/t.html:1:1: error: element <script> is not closed: add </script>"),
        errors("<script>a</\u017Fcript>"));
    assertEquals(List.of("dir/t.html:1:9: error: malformed markup: '</t\u0131tle' inside <title> looks like its end"
        + " tag but a browser doesn't end the element there: remove it"), errors("<title>a</t\u0131tle></title>"));
    // ASCII case does not matter.
    assertEquals(List.of("a=SCRIPT"), texts(parseOk("<script>a</SCRIPT>").children()));
  }
}
