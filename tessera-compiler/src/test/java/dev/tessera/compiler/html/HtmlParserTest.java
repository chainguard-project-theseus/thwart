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
  void commentsDoctypeCdataAndProcessingInstructions() {
    Document doc = parseOk("<?xml version=\"1.0\"?><!DOCTYPE html><!-- c --><a><![CDATA[x<y]]></a>");
    assertEquals(new DocType("<?xml version=\"1.0\"?>", pos(1, 1)), doc.children().get(0));
    assertEquals(new DocType("<!DOCTYPE html>", pos(1, 22)), doc.children().get(1));
    assertEquals(new Comment("<!-- c -->", pos(1, 37)), doc.children().get(2));
    Element a = el(doc.children().get(3));
    assertEquals(new Text("<![CDATA[x<y]]>", OutputContext.TEXT, pos(1, 50)), a.children().get(0));
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
    // TemplateGenerator checks <textarea/>, <title/> and the other raw text elements, where it knows
    // whether it writes them with an end tag (<textarea t:text="x"/>).
    assertEquals(List.of(), errors("<svg><title/></svg><textarea/>"));
  }

  private static String inSelect(String pos, String tag) {
    return "dir/t.html:" + pos + ": error: <" + tag + "> inside <select> is not supported: browsers with the classic"
        + " select parser ignore most tags there and parse their content as markup; move the <" + tag + "> out of"
        + " the <select> (a <select> holds only <option>, <optgroup>, <hr> and <t:block>)";
  }

  /**
   * {@code first}, then the error for a {@code <![CDATA[><script>[[...} at column {@code col} of
   * line 1: its '>'. The hole in it is the verifier's error (#411, {@code CdataHoleTest}).
   */
  private static List<String> withCdata(int col, String... first) {
    List<String> all = new ArrayList<>(List.of(first));
    all.add(
        "dir/t.html:1:" + (col + 9) + ": error: malformed markup: '>' inside a CDATA section is not supported, even in"
            + " <svg> and <math>: elsewhere a browser ends it at the first '>'; write the text without <![CDATA[,"
            + " with '>' as &gt;");
    return all;
  }

  @Test
  void selectHoldsOnlyOptionsAndOptgroups() {
    // Firefox 140 ignores other start tags in a select: the <script> in the title runs, and the
    // hole after the select lands in the <script> that the ignored <style> hid from Tessera.
    assertEquals(List.of(inSelect("1:9", "title")), errors("<select><title><script>[[${x}]]</script></title></select>"));
    assertEquals(List.of(inSelect("2:10", "style")),
        errors("<SELECT>\n<option>a<STYLE><script></STYLE></option></SELECT>[[${x}]]"));
    // By name, whatever the namespace: the classic select parser ignores <svg> and <math> too.
    assertEquals(List.of(inSelect("1:17", "svg"), inSelect("1:22", "title"), foreignMarkup(29, "title")),
        errors("<select><option><svg><title><script>[[${x}]]</script></title></svg></option></select>"));
    // The CDATA '>' is also an error on its own (in every browser the section ends early).
    assertEquals(withCdata(34, inSelect("1:29", "svg")),
        errors("<select><optgroup label=\"g\"><svg><![CDATA[><script>[[${x}]]</script>]]></svg></optgroup></select>"));
    assertEquals(withCdata(22, inSelect("1:9", "math"), inSelect("1:15", "mtext")),
        errors("<select><math><mtext><![CDATA[><script>[[${x}]]</script>]]></mtext></math></select>"));
    for (String tag : List.of("svg", "math", "button", "div", "noscript", "xmp")) {
      assertEquals(withCdata(9 + tag.length() + 2, inSelect("1:9", tag)),
          errors("<select><" + tag + "><![CDATA[><script>[[${x}]]</script>]]></" + tag + "></select>"));
    }
    // Start tags that close the select or run in every browser, and nested selects, are rejected too.
    assertEquals(
        List.of(inSelect("1:9", "input"), inSelect("1:16", "textarea"), inSelect("1:38", "script"),
            inSelect("1:58", "template"), inSelect("1:68", "title")),
        errors("<select><input><textarea>t</textarea><script>f()</script><template><title>t</title></template></select>"));
    assertEquals(List.of(inSelect("1:17", "select")), errors("<select><option><select></select></option></select>"));
    // t:remove on the select does not exempt its content.
    assertEquals(List.of(inSelect("1:24", "title")), errors("<select t:remove=\"tag\"><title>t</title></select>"));
    // A browser ignores the '/' and keeps the select open.
    assertEquals(
        withCdata(20, "dir/t.html:1:6: error: <select/> is not self-closing in HTML: the browser treats everything"
            + " after it as select; write <select ...></select>"),
        errors("<div><select/><svg><![CDATA[><script>[[${x}]]</script>]]></svg></div>"));
    // What a select may hold: options, groups, separators, t:block, text, comments and holes.
    assertEquals(
        List.of(),
        errors("<select><option>a</option><optgroup label=\"g\"><option>b</option></optgroup><hr>"
            + "<t:block t:each=\"o : ${os}\"><option t:text=\"${o}\">o</option></t:block><!-- c --> [[${x}]] </select>"
            + "<SELECT><OPTION>a<HR></OPTION></SELECT>"));
    // Elements around a select, or a select in a template's content, are not inside it.
    assertEquals(
        List.of(),
        errors("<template><select><option>a</option></select></template><select></select>"
            + "<title>[[${x}]]</title><style>p{}</style><svg><title>t</title></svg>"
            + "<table><title>[[${x}]]</title><style>p{}</style><tr><td><select></select></td></tr></table>"));
  }

  @Test
  void markupABrowserEndsAtItsFirstGreaterThanIsAnError() {
    // Outside svg and math a browser reads <![CDATA[ as a bogus comment ending at the first '>',
    // so it would run this <script> while Tessera escaped [[${x}]] as text.
    String cdata = "dir/t.html:1:15: error: malformed markup: '>' inside a CDATA section is not supported, even in"
        + " <svg> and <math>: elsewhere a browser ends it at the first '>'; write the text without <![CDATA[,"
        + " with '>' as &gt;";
    assertEquals(List.of(cdata), errors("<p><![CDATA[ x><script>[[${x}]]</script> ]]></p>"));
    // An expression in a CDATA section is the verifier's error, not the parser's (#411, CdataHoleTest).
    assertEquals(List.of(), errors("<div><![CDATA[ [[${x}]] ]]></div><svg><![CDATA[ [(${x})] ]]></svg><![CDATA[[${x}]]>"));
    assertEquals(List.of(cdata.replace("1:15", "2:2")), errors("<svg><![cdata[\n >]]></svg>"));
    String pi = "error: malformed markup: '>' inside '<?...?>' is not supported: a browser ends it at the first '>';"
        + " remove the '>'";
    assertEquals(List.of("dir/t.html:1:6: " + pi), errors("<?php><script>[[${x}]]</script> ?>"));
    assertEquals(List.of("dir/t.html:1:31: " + pi), errors("<?xml version=\"1.0\" encoding=\"><script>\"?>"));
    assertEquals(
        List.of("dir/t.html:1:24: error: malformed markup: '>' inside <!DOCTYPE> is not supported: a browser ends it"
            + " at the first '>'; write <!DOCTYPE html>"),
        errors("<!DOCTYPE html PUBLIC \"><script>\" \"x\">"));
    // Without an earlier '>', a browser ends them where Tessera does.
    assertEquals(
        List.of(),
        errors("<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"x.dtd\">"
            + "<p><![CDATA[ x < y ]]><?php echo 1 ?></p><svg><![CDATA[ a ]]]></svg>"
            + "<script>//<![CDATA[\nif (a > b) {}\n//]]></script>"));
  }

  @Test
  void textABrowserReadsAsABogusCommentIsAnError() {
    // A browser starts a bogus comment at "</ " that ends at the first '>', inside Tessera's
    // comment, so it would run this <script> while Tessera escaped [[${x}]] as text.
    String bogus = " starts a comment in a browser that ends at the first '>': write &lt; for a literal '<'";
    assertEquals(
        List.of("dir/t.html:1:6: error: malformed markup: '</' not followed by a letter" + bogus),
        errors("<p>a </ b <!-- > <script> -->[[${x}]]<!-- </script> --></p>"));
    assertEquals(
        List.of("dir/t.html:2:3: error: malformed markup: '</' not followed by a letter" + bogus),
        errors("<p>\n  </ x></p>"));
    assertEquals(List.of("dir/t.html:1:3: error: malformed markup: '<?'" + bogus), errors("a <? b ?>"));
    for (String start : List.of("<!-x>", "<!/x>", "<!?x>", "<!!x>", "<![if !IE]>")) {
      assertEquals(List.of("dir/t.html:1:6: error: malformed markup: '<!'" + bogus), errors("<i>a " + start + "</i>"));
    }
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
    // script while Tessera reads a JSON one.
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
    // which closes an svg element, so what follows is not in the element Tessera escaped it for.
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

  private static String hiddenEndTag(int line, int col, String tag) {
    return "dir/t.html:" + line + ":" + col + ": error: malformed markup: content of <" + tag + "> contains '</"
        + tag + "' before its end tag: a browser ends the element there, so the markup after it would be read"
        + " differently; remove it or move it outside the element";
  }

  @Test
  void browserRawTextEndTagMustBeTheElementsOwn() {
    // A browser reads <xmp>, <iframe>, <noembed>, <noframes> and <noscript> (scripting on) as raw
    // text up to the first </name; Tessera parses markup there, so that must be the element's end tag.
    assertEquals(List.of(hiddenEndTag(1, 16, "xmp")), errors("<xmp><i title=\"</xmp><script>/*\"></i></xmp>"));
    assertEquals(
        List.of(hiddenEndTag(2, 6, "NoScript")), errors("<NoScript>\n<!-- </NOSCRIPT><script> --></noscript>"));
    assertEquals(List.of(hiddenEndTag(1, 21, "noscript")), errors("<noscript><noscript></noscript></noscript>"));
    assertEquals(
        List.of(hiddenEndTag(1, 26, "iframe")), errors("<iframe><script>var a = \"</iframe>\";</script></iframe>"));
    parseOk("<noscript><img src=\"a.png\"></noscript><iframe src=\"/x\"></iframe><xmp>a <b>b</b></xmp>");
    parseOk("<noembed><!-- c --><p title=\"</noembedx>\"></p></noembed ><noframes></noframes>");
    parseOk("<div><noscript><p>x</p></NOSCRIPT></div><noscript><noembed></noembed></noscript>");
  }

  @Test
  void browserRawElementsEndWhereABrowserDoes() {
    // #283: a browser reads these as raw text up to the first </name, so an end tag hidden in a
    // nested raw-text element, a comment or an attribute value ends them there.
    for (String tag : List.of("xmp", "iframe", "noembed", "noframes", "noscript")) {
      for (String html : List.of(
          "<" + tag + "><textarea></" + tag + "><script>[[${x}]]</script></textarea></" + tag + ">",
          "<" + tag + "><title></" + tag + "><script>[[${x}]]</script></title></" + tag + ">",
          "<" + tag + "><!--</" + tag + "><script>[[${x}]]</script>--></" + tag + ">",
          "<" + tag + "><p title=\"</" + tag + "><script>/*\">*/[[${x}]]//</p></" + tag + ">")) {
        assertEquals(List.of(hiddenEndTag(1, html.indexOf("</" + tag) + 1, tag)), errors(html), html);
      }
    }
    assertEquals(List.of(hiddenEndTag(2, 5, "xmp")), errors("<xmp>\n<!--</XMP >--></xmp>"));
    // unquoted attribute value, and '/' after the name
    assertEquals(List.of(hiddenEndTag(1, 15, "xmp")), errors("<xmp><p title=</xmp>x</p></xmp>"));
    assertEquals(List.of(hiddenEndTag(1, 10, "xmp")), errors("<xmp><!--</xmp/>--></xmp>"));
    // browsers fold ASCII letters only: </noſcript> is no end tag to them, so the </noscript> in
    // the title is where a browser ends the element
    assertEquals(List.of(hiddenEndTag(1, 29, "noscript")), errors("<noscript></no\u017Fcript><title></noscript></title>"));

    Element noscript = el(parseOk("<noscript><img t:src=\"@{/p.gif}\"><p>[[${x}]]</p></noscript>").children().get(0));
    assertEquals("p", el(noscript.children().get(1)).name());
    parseOk("<iframe>[[${x}]]</iframe><xmp>[[${x}]]</xmp><noembed></noembed><noframes><p>a</p></noframes>");
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
    // Thymeleaf parser-level and prototype-only comment blocks
    parseOk("<!--/* hidden */--><!--/*/ <p>shown</p> /*/--><!--/**/-->");
    // inside raw text a comment is text to a browser
    parseOk("<textarea><!--> --></textarea><title><!-- --!> --></title>");
  }

  private static String foreignMarkup(int col, String tag) {
    return "dir/t.html:1:" + col + ": error: malformed markup: '<' inside <" + tag + "> in <svg> or <math> starts"
        + " markup in a browser, not text: write &lt; for a literal '<'";
  }

  @Test
  void markupInForeignRawTextIsAnError() {
    // SVG <title> is an HTML integration point: a browser runs this script.
    assertEquals(List.of(foreignMarkup(13, "title")), errors("<svg><title><script>[[${x}]]</script></title></svg>"));
    assertEquals(foreignMarkup(13, "title"),
        errors("<svg><title><script x=a\">[[${x}]]</script>\" ></title></svg>").get(0));
    // Inside svg and math these are foreign elements, and <p>/<img> leave foreign content.
    assertEquals(List.of(foreignMarkup(13, "style")),
        errors("<svg><style><p><script>/*</style>*/[[${x}]]//<script></script></svg>"));
    assertEquals(List.of(foreignMarkup(17, "textarea")),
        errors("<math><textarea><p><script>[[${x}]]</script></p></textarea></math>"));
    assertEquals(List.of(foreignMarkup(16, "textarea")),
        errors("<svg><textarea><img src=x onerror=[[${x}]]></textarea></svg>"));
    assertEquals(List.of(foreignMarkup(14, "title")),
        errors("<math><title><img src=x onerror=[[${x}]]></title></math>"));
    assertEquals(List.of(foreignMarkup(19, "script")), errors("<svg><script>if (a<b) {}</script></svg>"));
    assertEquals(List.of(foreignMarkup(13, "style")), errors("<svg><style><!-- a{} --></style></svg>"));
    assertEquals(List.of(foreignMarkup(13, "style")), errors("<svg><style></ a{}</style></svg>"));
    assertEquals(List.of(foreignMarkup(14, "script")), errors("<svg><script><![CDATA[ f() </script></svg>"));
    // Namespaces: mglyph stays MathML in <mi>, and <math> inside <svg> is an SVG element.
    assertEquals(List.of(foreignMarkup(29, "textarea")),
        errors("<math><mi><mglyph><textarea><b>x</b></textarea></mglyph></mi></math>"));
    assertEquals(List.of(foreignMarkup(26, "textarea")),
        errors("<svg><math><mi><textarea><b>x</b></textarea></mi></math></svg>"));
    // annotation-xml is an HTML integration point only with a static HTML encoding (ASCII case-insensitive).
    assertEquals(List.of(foreignMarkup(58, "textarea")),
        errors("<math><annotation-xml encoding=\"image/svg+xml\"><textarea><b>x</b></textarea></annotation-xml></math>"));
    assertEquals(List.of(foreignMarkup(69, "textarea")), errors(
        "<math><annotation-xml encoding=\"text/html\" t:encoding=\"e\"><textarea><b>x</b></textarea></annotation-xml></math>"));
    assertEquals(List.of(foreignMarkup(66, "textarea")), errors(
        "<math><annotation-xml encoding=\"appl\u0131cation/xhtml+xml\"><textarea><b>x</b></textarea></annotation-xml></math>"));
    // A tag that is not rendered is not the parent the browser sees.
    assertEquals(List.of(foreignMarkup(43, "title")),
        errors("<svg><foreignObject t:remove=\"tag\"><title><script>[[${x}]]</script></title></foreignObject></svg>"));
    assertEquals(List.of(foreignMarkup(36, "textarea")),
        errors("<math><mi t:remove=\"tag\"><textarea><img src=x onerror=[[${x}]]></textarea></mi></math>"));
    assertEquals(List.of(foreignMarkup(38, "textarea")), errors(
        "<math><mi><t:block><mglyph><textarea><p>[[${x}]]</p></textarea></mglyph></t:block></mi></math>"));
  }

  @Test
  void cdataInForeignTitleIsLeftToTheVerifier() {
    // A CDATA section in an SVG <title>, an integration point where browsers disagree, is the
    // verifier's exclusion (d) (#441, verify/CdataHoleTest); here it is raw text without markup.
    parseOk("<svg><title><![CDATA[ > <script>[[${x}]]</script> ]]></title></svg>");
    parseOk("<svg><title><![CDATA[ a < b ]]> [[${x}]]</title></svg>");
    // Script and style keep CDATA with '>': they are not integration points.
    parseOk("<svg><script><![CDATA[ if (a > b) { f([[${x}]]); } ]]></script>"
        + "<style><![CDATA[ a > b { fill: red } ]]></style></svg>");
  }

  @Test
  void foreignRawTextWithoutMarkupStillParses() {
    Element svg = el(parseOk("<svg><title>a &lt; b [[${x}]]</title><desc>d</desc><style>.a > b { fill: red }</style>"
        + "<script><![CDATA[ if (a < b && c) { f(\"<b>\"); } ]]></script><textarea>a < b</textarea><title/></svg>")
        .rootElement());
    assertTrue(svg.foreign());
    List<Element> kids = svg.children().stream().map(HtmlParserTest::el).toList();
    assertTrue(kids.stream().allMatch(Element::foreign));
    // Foreign <title> and <textarea> are TEXT, so {{ is broken there (#277).
    assertEquals(
        List.of(OutputContext.TEXT, OutputContext.TEXT, OutputContext.STYLE, OutputContext.SCRIPT, OutputContext.TEXT),
        kids.subList(0, 5).stream().map(e -> ((Text) e.children().get(0)).context()).toList());

    // Integration points: their children are HTML again.
    Element fo = el(el(parseOk("<svg><foreignObject><div><title><b>t</b></title><textarea><i></i></textarea>"
        + "<style>a>b{}</style><script>if (a<b) {}</script></div></foreignObject></svg>").rootElement())
        .children().get(0));
    assertTrue(fo.foreign());
    Element div = el(fo.children().get(0));
    assertFalse(div.foreign());
    assertEquals(
        List.of(OutputContext.RCDATA, OutputContext.RCDATA, OutputContext.STYLE, OutputContext.SCRIPT),
        div.children().stream().map(n -> ((Text) el(n).children().get(0)).context()).toList());
    parseOk("<math><mtext><textarea><b>x</b></textarea></mtext><mi><title><i>t</i></title></mi></math>");
    parseOk("<svg><desc><b>x</b></desc></svg>");
    parseOk("<svg><t:block><foreignObject t:remove=\"none\"><t:block><title><b>t</b></title></t:block>"
        + "</foreignObject></t:block></svg>");
    parseOk("<math><annotation-xml><svg><title>t</title></svg></annotation-xml></math><title><b>html</b></title>");
  }

  private static String breakout(int col, String tag) {
    return "dir/t.html:1:" + col + ": error: malformed markup: <" + tag + "> inside <svg> or <math> is HTML in a"
        + " browser and ends the <svg> or <math> there: close it first, or put HTML inside <foreignObject>";
  }

  @Test
  void breakoutTagInForeignContentIsAnError() {
    // A browser closes the <svg> at <p>, so this <title> is an HTML RCDATA title, not SVG text.
    assertEquals(List.of(breakout(6, "p")), errors("<svg><p><title>[[${x}]]</title></p></svg>"));
    assertEquals(List.of(breakout(9, "DIV")), errors("<svg><g><DIV>x</DIV></g></svg>"));
    assertEquals(List.of(breakout(6, "br")), errors("<svg><br></svg>"));
    assertEquals(List.of(breakout(7, "img")), errors("<math><img src=\"a.png\"/></math>"));
    assertEquals(List.of(breakout(6, "font")), errors("<svg><font color=\"red\">x</font></svg>"));
    assertEquals(List.of(breakout(6, "font")), errors("<svg><font t:attr=\"size=${x}\">x</font></svg>"));
    assertEquals(List.of(breakout(15, "span")), errors("<svg><t:block><span>x</span></t:block></svg>"));
    assertEquals(List.of(breakout(23, "p")), errors("<math><annotation-xml><p>x</p></annotation-xml></math>"));
    // Under an integration point, or with the tag not rendered, it stays where it is.
    parseOk("<svg><foreignObject><p><b>x</b><br></p></foreignObject><desc><i>d</i></desc><font>f</font></svg>");
    parseOk("<math><mi><b>x</b></mi><annotation-xml encoding=\"TEXT/HTML\"><div><style>.x::before{content:\"<b>\"}</style>"
        + "</div></annotation-xml></math>");
    parseOk("<svg><g t:remove=\"tag\"><text>t</text></g><p t:remove=\"tag\"><text>t</text></p></svg>");
  }

  @Test
  void breakoutsFromPr291() {
    assertEquals(List.of(breakout(6, "p")), errors("<svg><p t:remove=\"all\"></p><title>x</title></svg>"));
    assertEquals(List.of(breakout(6, "p")), errors("<svg><p t:if=\"${x} == null\"></p><title>x</title></svg>"));
    assertEquals(List.of(breakout(6, "font")), errors("<svg><font t:size=\"${x}\"></font></svg>"));
    assertEquals(List.of(breakout(6, "font")), errors("<svg><font t:attr=\"face=${x}\"></font></svg>"));
    assertEquals(List.of(breakout(7, "p")), errors("<math><p></p></math>"));
    parseOk("<svg><foreignObject><p>x</p></foreignObject><desc><b>d</b></desc><font></font></svg>");
    parseOk("<math><mtext><p>x</p></mtext><annotation-xml encoding=\"text/html\"><div>d</div></annotation-xml></math>");
  }

  @Test
  void markupInForeignRawTextCasesFromPr291() {
    // Each case: the template, the foreign raw-text element and the '<' a browser parses as markup.
    String[][] cases = {
      {"<svg><textarea><script>[[${x}]]</script></textarea></svg>", "textarea", "<script>"},
      {"<math><title><script>[[${x}]]</script></title></math>", "title", "<script>"},
      {"<math><textarea><script>[[${x}]]</script></textarea></math>", "textarea", "<script>"},
      {"<SVG><g><Title><script>[[${x}]]</script></Title></g></SVG>", "Title", "<script>"},
      // MathML <title> is not an integration point; annotation-xml is one only for HTML.
      {"<math><title><textarea><script>[[${x}]]</script></textarea></title></math>", "title", "<textarea>"},
      {"<math><annotation-xml><textarea><script>[[${x}]]</script></textarea></annotation-xml></math>", "textarea",
          "<script>"},
      {"<math><annotation-xml encoding=\"text/xml\"><title><script>[[${x}]]</script></title></annotation-xml></math>",
          "title", "<script>"},
      // mglyph stays MathML in <mi>.
      {"<math><mi><mglyph><textarea><script>[[${x}]]</script></textarea></mglyph></mi></math>", "textarea",
          "<script>"},
      // <svg> inside annotation-xml is SVG again.
      {"<math><annotation-xml><svg><title><script>[[${x}]]</script></title></svg></annotation-xml></math>", "title",
          "<script>"},
      // A bare <font> stays in SVG.
      {"<svg><font></font><textarea><script>[[${x}]]</script></textarea></svg>", "textarea", "<script>"},
      // Without its tag, the SVG <title> is still SVG: its content is the browser's <svg><textarea>.
      {"<svg><title t:remove=\"tag\"><textarea><script>[[${x}]]</script></textarea></title></svg>", "title",
          "<textarea>"},
      {"<math><mi><t:block><mglyph><textarea><img src=\"x\" onerror=\"[[${x}]]\"></textarea></mglyph></t:block></mi>"
          + "</math>", "textarea", "<img"},
    };
    for (String[] c : cases) {
      assertEquals(List.of(foreignMarkup(c[0].indexOf(c[2]) + 1, c[1])), errors(c[0]), c[0]);
    }
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
    // In an integration point (or outside the svg) these are HTML elements: RCDATA, raw text.
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
      assertEquals(List.of("<i>x=RCDATA"), texts(parseOk(html).children()), html);
    }
    parseOk("<math><annotation-xml t:remove=\"tag\"><svg></svg></annotation-xml></math>");
    parseOk("<math><mi><t:block><mglyph></mglyph></t:block></mi></math>");
    parseOk("<svg><t:block><g></g></t:block></svg>");
  }

  @Test
  void foreignTitleTakesTheNamespaceABrowserGivesIt() {
    // <svg> directly in <math> is a MathML element, so its <title> is a MathML title: TEXT.
    assertEquals(List.of("[[${x}]]=TEXT"), texts(parseOk("<math><svg><title>[[${x}]]</title></svg></math>").children()));
    assertEquals(List.of("[[${x}]]=TEXT"), texts(parseOk("<svg><math><title>[[${x}]]</title></math></svg>").children()));
    // A computed encoding may or may not be HTML: Tessera takes the stricter, MathML reading.
    for (String html :
        List.of(
            "<math><annotation-xml t:encoding=\"${x}\"><title>[[${x}]]</title></annotation-xml></math>",
            "<math><annotation-xml t:attr=\"encoding=${x}\"><title>[[${x}]]</title></annotation-xml></math>")) {
      assertEquals(List.of("[[${x}]]=TEXT"), texts(parseOk(html).children()), html);
    }
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

  private static String foreignReference(int col, String tag) {
    return "dir/t.html:1:" + col + ": error: malformed markup: '&' inside <" + tag + "> in <svg> or <math> is decoded"
        + " by a browser: write the character itself, or use a CDATA section";
  }

  @Test
  void characterReferenceInForeignScriptOrStyleIsAnError() {
    // A browser decodes &quot; in an SVG <script> to a quote that Tessera's JS lexer did not see.
    assertEquals(List.of(foreignReference(22, "script")),
        errors("<svg><script>var a = &quot;[[${x}]]&quot;;</script></svg>"));
    // After a CDATA section the text is decoded again.
    assertEquals(List.of(foreignReference(39, "script")),
        errors("<svg><script><![CDATA[ var a = 1; ]]> &quot;[[${x}]]&quot;</script></svg>"));
    assertEquals(List.of(foreignReference(23, "script")),
        errors("<math><script>var a = &#39;[[${x}]]&#39;;</script></math>"));
    assertEquals(List.of(foreignReference(35, "style")),
        errors("<svg><style>.a::before { content: &quot;x&quot; }</style></svg>"));
    assertEquals(List.of(foreignReference(17, "STYLE")), errors("<math><STYLE>a{}&#10;</STYLE></math>"));
    // A '&' that is not a reference is still decoded text; fail closed on any '&'.
    assertEquals(List.of(foreignReference(20, "script")), errors("<svg><script>if (a && b) {}</script></svg>"));
    // A CDATA section is not decoded, and in HTML content a script is raw text.
    parseOk("<svg><script><![CDATA[ var a = \"&quot;\" && [[${x}]]; ]]></script>"
        + "<style><![CDATA[ a::before { content: \"&\" } ]]></style></svg>");
    parseOk("<script>var a = \"&quot;\" && [[${x}]];</script><style>a::before { content: \"&amp;\" }</style>");
    parseOk("<svg><foreignObject><script>if (a && b) {}</script></foreignObject></svg>");
    // Title and textarea are TEXT in svg: both readings decode there.
    parseOk("<svg><title>a &amp; b</title><textarea>&quot;</textarea></svg>");
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
