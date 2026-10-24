package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlParserTest {

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
        <html xmlns:t="https://tessera.dev" t:params="OwnerView owner, List<PetView> pets">
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
          <svg><![CDATA[ x < y ]]></svg>
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
        List.of(OutputContext.RCDATA, OutputContext.RCDATA, OutputContext.STYLE, OutputContext.FORBIDDEN),
        top.stream().map(n -> ((Text) el(n).children().get(0)).context()).toList());
    assertEquals(0, el(parseOk("<script src=\"/a.js\"></script>").rootElement()).children().size());
  }

  @Test
  void foreignTitleAndTextareaContentIsMarkup() {
    // #277: under <svg>/<math> a browser parses <title> and <textarea> content as markup (an SVG
    // <title> is an HTML integration point, the others are ordinary foreign elements), so a
    // nested <script> is a real script and their text is element text.
    Document doc = parseOk("<svg><title>a<b>c</b></title></svg>");
    Element svg = el(doc.rootElement());
    assertFalse(svg.inForeignContent());
    assertTrue(svg.hasForeignContent());
    Element title = el(svg.children().get(0));
    assertTrue(title.inForeignContent());
    assertFalse(title.hasForeignContent());
    Element b = el(title.children().get(1));
    assertEquals("b", b.name());
    assertEquals(pos(1, 14), b.pos());
    assertEquals(pos(1, 17), b.children().get(0).pos());
    assertEquals(List.of("a=TEXT", "c=TEXT"), texts(doc.children()));
    for (String html :
        List.of(
            "<svg><title><script>[[${x}]]</script></title></svg>",
            "<svg><textarea><script>[[${x}]]</script></textarea></svg>",
            "<math><title><script>[[${x}]]</script></title></math>",
            "<math><textarea><script>[[${x}]]</script></textarea></math>",
            "<SVG><g><Title><script>[[${x}]]</script></Title></g></SVG>",
            // MathML <title> is not an integration point; annotation-xml is one only for HTML.
            "<math><title><textarea><script>[[${x}]]</script></textarea></title></math>",
            "<math><annotation-xml><textarea><script>[[${x}]]</script></textarea></annotation-xml></math>",
            "<math><annotation-xml encoding=\"text/xml\"><title><script>[[${x}]]</script></title></annotation-xml></math>",
            // mglyph/malignmark stay MathML in a text integration point.
            "<math><mi><mglyph><textarea><script>[[${x}]]</script></textarea></mglyph></mi></math>",
            // <svg> starts foreign content again inside an integration point.
            "<svg><foreignObject><svg><title><script>[[${x}]]</script></title></svg></foreignObject></svg>",
            "<math><annotation-xml><svg><title><script>[[${x}]]</script></title></svg></annotation-xml></math>",
            // <font> leaves foreign content only with color, face or size.
            "<svg><font></font><textarea><script>[[${x}]]</script></textarea></svg>")) {
      assertEquals(List.of("[[${x}]]=SCRIPT"), texts(parseOk(html).children()), html);
    }
  }

  @Test
  void htmlTitleAndTextareaInsideForeignContentStayRawText() {
    // Integration points (SVG foreignObject/desc/title, MathML mi/mo/mn/ms/mtext and
    // annotation-xml with an HTML encoding) hold HTML: a <title>/<textarea> there is RCDATA, raw text.
    for (String html :
        List.of(
            "<svg><foreignObject><textarea><i>x</textarea></foreignObject></svg>",
            "<svg><FOREIGNOBJECT><textarea><i>x</textarea></FOREIGNOBJECT></svg>",
            "<svg><desc><title><i>x</title></desc></svg>",
            "<svg><title><textarea><i>x</textarea></title></svg>",
            "<math><mi><textarea><i>x</textarea></mi></math>",
            "<math><mo><textarea><i>x</textarea></mo></math>",
            "<math><mn><textarea><i>x</textarea></mn></math>",
            "<math><ms><textarea><i>x</textarea></ms></math>",
            "<math><mtext><textarea><i>x</textarea></mtext></math>",
            "<math><annotation-xml encoding=\"text/html\"><title><i>x</title></annotation-xml></math>",
            "<math><annotation-xml encoding=\"Application/XHTML+XML\"><title><i>x</title></annotation-xml></math>",
            "<svg></svg><textarea><i>x</textarea>",
            "<svg><foreignObject><p></p><textarea><i>x</textarea></foreignObject></svg>")) {
      assertEquals(List.of("<i>x=RCDATA"), texts(parseOk(html).children()), html);
    }
  }

  @Test
  void hiddenEndTagInHtmlTextareaInsideForeignContentIsAnError() {
    // The naive "inside <svg>" rule would parse these as markup and miss the </textarea that a
    // browser ends the HTML textarea at, putting the TEXT-escaped value in a live script.
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
  void markupWhoseNamespaceDependsOnRenderingIsAnError() {
    // An HTML start tag such as <p> ends <svg>/<math> in a browser, and t:if or t:remove may
    // drop it, so the namespace of what follows would depend on rendering.
    String breakout = "dir/t.html:1:6: error: malformed markup: <%s> inside SVG or MathML content ends it in a"
        + " browser: close the <svg> or <math> first, or put the HTML inside <foreignObject> or <mtext>";
    for (String tag : List.of("p", "br", "div", "img", "span", "table", "B", "font")) {
      String open = tag.equals("font") ? "<font color=\"red\">" : "<" + tag + ">";
      String close = tag.equals("br") || tag.equals("img") ? "" : "</" + tag + ">";
      assertEquals(List.of(String.format(breakout, tag)), errors("<svg>" + open + close + "<title>x</title></svg>"), tag);
    }
    assertEquals(List.of(String.format(breakout, "p")), errors("<svg><p t:remove=\"all\"></p><title>x</title></svg>"));
    assertEquals(List.of(String.format(breakout, "font")), errors("<svg><font t:size=\"s\"></font></svg>"));
    assertEquals(List.of(String.format(breakout, "font")), errors("<svg><font t:attr=\"face=s\"></font></svg>"));
    assertEquals(
        List.of("dir/t.html:1:7: error: malformed markup: <p> inside SVG or MathML content ends it in a browser:"
            + " close the <svg> or <math> first, or put the HTML inside <foreignObject> or <mtext>"),
        errors("<math><p></p></math>"));
    // Not in foreign content, or not a breakout tag: fine.
    parseOk("<svg><foreignObject><p>x</p></foreignObject><desc><b>d</b></desc><font></font></svg>");
    parseOk("<math><mtext><p>x</p></mtext><annotation-xml encoding=\"text/html\"><div>d</div></annotation-xml></math>");

    // <svg> directly in MathML content is a MathML element, <math> in SVG an SVG one.
    assertEquals(
        List.of("dir/t.html:1:7: error: malformed markup: <svg> inside MathML content is not a real <svg> in a browser:"
            + " put it inside <foreignObject> (SVG) or <annotation-xml> (MathML)"),
        errors("<math><svg><title>x</title></svg></math>"));
    assertEquals(
        List.of("dir/t.html:1:9: error: malformed markup: <math> inside SVG content is not a real <math> in a browser:"
            + " put it inside <foreignObject> (SVG) or <annotation-xml> (MathML)"),
        errors("<svg><g><math></math></g></svg>"));
    parseOk("<svg><svg></svg><foreignObject><math><mi><svg></svg></mi></math></foreignObject></svg>"
        + "<math><math></math><mi><svg></svg></mi><annotation-xml><svg></svg></annotation-xml></math>");

    // The encoding decides whether annotation-xml holds HTML.
    String encoding = "dir/t.html:1:7: error: a computed encoding on <annotation-xml> is not supported: it decides"
        + " whether a browser parses the content as HTML; write a fixed encoding";
    assertEquals(List.of(encoding), errors("<math><annotation-xml encoding=\"text/html\" t:attr=\"encoding=e\"></annotation-xml></math>"));
    assertEquals(List.of(encoding), errors("<math><annotation-xml t:encoding=\"e\"></annotation-xml></math>"));
    parseOk("<math><annotation-xml encoding=\"text/html\" t:attr=\"id=e\"></annotation-xml></math>");

    // Without its tag, the content of an element that changes the namespace would land in another one.
    String removeTag = "error: t:remove=\"tag\" is not supported on <%s>: without its tag a browser would parse the"
        + " content in another namespace (HTML or SVG/MathML); remove the whole element or keep its tag";
    assertEquals(List.of("dir/t.html:1:1: " + String.format(removeTag, "svg")),
        errors("<svg t:remove=\"tag\"><title>x</title></svg>"));
    assertEquals(List.of("dir/t.html:1:1: " + String.format(removeTag, "math")), errors("<math t:remove=\"tag\"></math>"));
    assertEquals(List.of("dir/t.html:1:6: " + String.format(removeTag, "title")),
        errors("<svg><title t:remove=\"tag\"><textarea>x</textarea></title></svg>"));
    assertEquals(List.of("dir/t.html:1:6: " + String.format(removeTag, "foreignObject")),
        errors("<svg><foreignObject t:remove=\"tag\"></foreignObject></svg>"));
    assertEquals(List.of("dir/t.html:1:7: " + String.format(removeTag, "mtext")),
        errors("<math><mtext t:remove=\"tag\"></mtext></math>"));
    // mglyph/malignmark keep MathML inside a text integration point; their content would be HTML without them.
    assertEquals(List.of("dir/t.html:1:11: " + String.format(removeTag, "mglyph")),
        errors("<math><mi><mglyph t:remove=\"tag\"><textarea>x</textarea></mglyph></mi></math>"));
    assertEquals(List.of("dir/t.html:1:11: " + String.format(removeTag, "malignmark")),
        errors("<math><mo><malignmark t:remove=\"tag\"></malignmark></mo></math>"));
    // annotation-xml makes a child <svg> SVG; directly inside <math> it would be MathML.
    assertEquals(List.of("dir/t.html:1:7: " + String.format(removeTag, "annotation-xml")),
        errors("<math><annotation-xml t:remove=\"tag\"><svg></svg></annotation-xml></math>"));
    parseOk("<svg><g t:remove=\"tag\"><textarea t:remove=\"tag\">x</textarea></g><svg t:remove=\"tag\"></svg></svg>");
    parseOk("<math><mrow t:remove=\"tag\"><mn>1</mn></mrow></math>");
    // <t:block> renders no tag either.
    assertEquals(
        List.of("dir/t.html:1:11: error: <t:block> is not supported here: it renders no tag, so a browser would parse"
            + " its content in another namespace (HTML or SVG/MathML); put t:if, t:each or t:with on the child elements"
            + " instead"),
        errors("<math><mi><t:block><mglyph></mglyph></t:block></mi></math>"));
    parseOk("<svg><t:block><g></g></t:block></svg><math><mrow><t:block><mi>x</mi></t:block></mrow></math>");
  }

  @Test
  void commentsDoctypeCdataAndProcessingInstructions() {
    Document doc = parseOk("<?xml version=\"1.0\"?><!DOCTYPE html><!-- c --><a><![CDATA[<x>]]></a>");
    assertEquals(new DocType("<?xml version=\"1.0\"?>", pos(1, 1)), doc.children().get(0));
    assertEquals(new DocType("<!DOCTYPE html>", pos(1, 22)), doc.children().get(1));
    assertEquals(new Comment("<!-- c -->", pos(1, 37)), doc.children().get(2));
    Element a = el(doc.children().get(3));
    assertEquals(new Text("<![CDATA[<x>]]>", OutputContext.TEXT, pos(1, 50)), a.children().get(0));
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
    assertEquals("&unknown; &copy; &amp &", HtmlParser.decode("&unknown; &copy; &amp &"));
    assertEquals("&#; &#x; &#xZ;", HtmlParser.decode("&#; &#x; &#xZ;"));
    assertEquals("���", HtmlParser.decode("&#0;&#xD800;&#x110000;"));
    assertEquals("�", HtmlParser.decode("&#99999999999999999999;"));
    assertEquals("&lt;", HtmlParser.decode("&amp;lt;"));
    assertEquals("plain", HtmlParser.decode("plain"));
  }

  @Test
  void attributeValueDecodedRawKept() {
    Element a = el(parseOk("<a title=\"&#106;avascript&colon;\" t:text=\"${a &gt; b}\"></a>").rootElement());
    assertEquals("javascript&colon;", a.attributes().get(0).value());
    assertEquals("&#106;avascript&colon;", a.attributes().get(0).rawValue());
    assertEquals("${a > b}", a.attributes().get(1).value());
  }

  // ---- errors (negative tests) ----

  @Test
  void selfClosingScriptOrStyleIsAnError() {
    // A browser treats <script/> as an open tag, so text after it would run as script.
    assertEquals(
        List.of("dir/t.html:1:1: error: <script/> is not self-closing in HTML: the browser treats everything"
            + " after it as script; write <script ...></script>"),
        errors("<script/>[[${x}]]<script>f()</script>"));
    assertEquals(
        List.of("dir/t.html:2:3: error: <script/> is not self-closing in HTML: the browser treats everything"
            + " after it as script; write <script ...></script>"),
        errors("<p></p>\n  <SCRIPT src=\"a.js\"/>"));
    assertEquals(
        List.of("dir/t.html:1:6: error: <style/> is not self-closing in HTML: the browser treats everything"
            + " after it as style; write <style ...></style>"),
        errors("<div><style/></div>"));
    assertEquals(List.of(), errors("<script></script><style></style>"));
    // RCDATA elements stay allowed: TEXT escaping is still safe there, and SVG <title/> is common.
    assertEquals(List.of(), errors("<svg><title/></svg><textarea/>"));
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
  void rawTextEndTagHiddenFromAttoparserIsAnError() {
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
    assertEquals(
        List.of("dir/t.html:1:32: error: malformed markup: content of <script> contains"
            + " '</script' before its end tag: escape it (in a script string, write '<\\/script')"),
        errors("<script type=\"text/html\"><a b=\"</script><p>\"></script>"));
    parseOk("<textarea></textareax></textarea>");
  }

  @Test
  void commentInsideScriptIsAnError() {
    assertEquals(
        List.of(
            "dir/t.html:1:9: error: malformed markup: '<!--' inside <script> is not supported: remove it (it is not needed in HTML5)",
            "dir/t.html:1:48: error: end tag </script> does not match any open element: remove it or add the matching <script>"),
        errors("<script><!--<script></script><p>[[${x}]]</p>--></script>"));
  }
}
