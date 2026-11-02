package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.SafeHtml;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A fragment is compiled in the parsing context it is declared in (HTML, {@code <svg>},
 * {@code <math>}, raw text), but where it lands is the verifier's check: it replays the fragment's
 * skeleton, holes and their escapers included, in the caller's real parser state. In {@code <svg>},
 * {@code <title>} holds HTML, so an HTML fragment's {@code <title><script>[[s]]</script></title>}
 * would run {@code s} as script there; nothing inside {@code <svg>} or {@code <math>} is an
 * insertion point (#678), so that is rejected before it gets there. Static, hole-free markup
 * declared in one context and inserted in another is the author's own markup: it is accepted
 * when the composed page parses without errors.
 */
class FragmentParsingContextTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static final String FRAGS =
      "<div " + NS + ">\n"
          + "<a t:fragment=\"x(String s)\"><title><script>[[s]]</script></title></a>\n"
          + "<i t:fragment=\"plain\">plain</i>\n"
          + "<svg t:fragment=\"check\" viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg>\n"
          + "<svg><g t:fragment=\"r(String s)\"><text>r</text></g>"
          + "<t:block t:fragment=\"paths\"><path d=\"M0 0\"/></t:block></svg>\n"
          + "<math><mrow t:fragment=\"row\"><mi>x</mi></mrow></math>\n"
          + "<span t:fragment=\"sel(String s)\"><style><script></style>[[s]]</span>\n"
          + "<option t:fragment=\"opt(String s)\" t:text=\"s\">x</option><t:block t:fragment=\"opts\"><option>a</option></t:block>\n"
          + "<section t:fragment=\"card(Fragment content)\"><div t:replace=\"${content}\">c</div></section>\n"
          + "</div>";

  private static Compilation page(String body) {
    return Compilation.builder()
        .template("frags.html", FRAGS)
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n" + body + "\n</div>")
        .compile();
  }

  @Test
  void htmlFragmentInsideSvgIsRejected() {
    String svg = "a value, condition, loop or fragment inside <svg> is not supported";
    String math = "a value, condition, loop or fragment inside <math> is not supported";
    page("<svg><g t:insert=\"~{frags :: x(v)}\"></g></svg>").assertError("page.html:2:19", svg);
    page("<svg t:insert=\"~{frags :: x(v)}\"></svg>").assertError("page.html:2:16", svg);
    page("<svg><g t:replace=\"~{frags :: x(v)}\"></g></svg>").assertError("page.html:2:20", svg);
    page("<math><mrow t:insert=\"~{frags :: plain}\"></mrow></math>").assertError("page.html:2:23", math);
    page("<math><mrow t:insert=\"~{frags :: check}\"></mrow></math>").assertError("page.html:2:23", math);
    page("<math><mi t:insert=\"~{frags :: row}\"></mi></math>").assertError("page.html:2:21", math);
  }

  @Test
  void htmlTitleFragmentReplacingInsideSvgIsRejected() {
    // An SVG <title> holds HTML, so the RCDATA-escaped hole would run inside the <script>.
    String svg = "a value, condition, loop or fragment inside <svg> is not supported";
    Compilation.builder()
        .template("frag.html", "<title " + NS + " t:fragment=\"payload(String x)\"><script>[[x]]//</script></title>")
        .template("page.html", "<div " + NS + " t:params=\"String x\">\n<svg><g t:replace=\"~{frag :: payload(x)}\"></g></svg></div>")
        .compile()
        .assertError("page.html:2:20", svg);
    page("<svg><g t:replace=\"~{::f(v)}\"></g></svg><title t:fragment=\"f(String s)\"><script>[[s]]</script></title>")
        .assertError("page.html:2:20", svg);
  }

  @Test
  void svgFragmentInsideHtmlIsTheVerifiersCheck() {
    // Static SVG markup in HTML content is the author's own markup: <g><text>r</text></g> parses
    // without errors there, and holds no hole whose escaper could be wrong.
    page("<div t:insert=\"~{frags :: r(v)}\"></div>").assertSuccess();
    // <path/> is not void in HTML: the verifier rejects the composed page at the call.
    page("<p t:replace=\"~{frags :: paths}\"></p>")
        .assertError("page.html:2:15", "in fragment frags :: paths, inserted here, at frags.html:5:80: a browser ignores"
            + " the '/' of <path/>");
  }

  @Test
  void fragmentInsideRawTextIsTheRawTextError() {
    // The raw-text rule (BrowserRawTextTest) reports these; this rule adds nothing of its own.
    rawTextError(page("<noscript><div t:insert=\"~{frags :: plain}\"></div></noscript>"), "page.html:2:16", "t:insert",
        "noscript");
    rawTextError(page("<iframe><b t:replace=\"~{frags :: plain}\"></b></iframe>"), "page.html:2:12", "t:replace", "iframe");
    rawTextError(page("<title t:insert=\"~{frags :: plain}\"></title>"), "page.html:2:8", "t:insert", "title");
    rawTextError(page("<textarea t:insert=\"~{frags :: plain}\"></textarea>"), "page.html:2:11", "t:insert", "textarea");
    rawTextError(
        Compilation.builder()
            .template("page.html", "<div " + NS + " t:params=\"Fragment f\">\n<title t:insert=\"${f}\"></title></div>")
            .compile(),
        "page.html:2:8", "t:insert", "title");
    rawTextError(page("<svg><title t:insert=\"~{frags :: r(v)}\"></title></svg>"), "page.html:2:13", "t:insert", "title");
    rawTextError(page("<noscript><b t:fragment=\"n\">x</b></noscript><noscript><i t:insert=\"~{:: n}\"></i></noscript>"),
        "page.html:2:", "t:insert", "noscript");
    // A fragment declared in raw text and inserted in HTML content: the verifier replays its markup
    // and holes there, where the text escaper it was compiled with is the one HTML text needs.
    page("<noscript><b t:fragment=\"n\">x</b></noscript><div t:insert=\"~{:: n}\"></div>").assertSuccess();
    Compilation hole =
        page("<noscript><b t:fragment=\"n(String s)\">[[s]]</b></noscript><div t:insert=\"~{:: n(v)}\"></div>").assertSuccess();
    assertTrue(hole.render("page", Map.of("v", "</noscript><script>")).contains("<div><b>&lt;/noscript&gt;&lt;script&gt;</b></div>"));
    page("<svg><noscript><g t:fragment=\"n\"><path/></g></noscript></svg><svg t:insert=\"~{:: n}\"></svg>")
        .assertError("page.html:2:", "a value, condition, loop or fragment inside <svg> is not supported");
  }

  /** {@code c} has the raw-text rule's error for {@code what} inside {@code <element>}, and no error of this rule. */
  private static void rawTextError(Compilation c, String position, String what, String element) {
    c.assertError(position, what + " inside <" + element + "> is not supported: a browser reads the content of <"
        + element + "> as text up to the first '</" + element + "'");
    assertTrue(c.errors().stream().noneMatch(e -> e.contains("where a browser parses")), c.errors().toString());
  }

  @Test
  void fragmentUnderReplacedSvgIsDeclaredInSvg() {
    // The children of <svg t:replace> are compiled as SVG content (where, say, <![CDATA[ is text), so
    // a value in them is a value inside <svg> (#678), wherever the fragment is inserted.
    String x = "<g t:fragment=\"x(String v)\"><text>[[v]]</text></g>";
    String message = "a value, condition, loop or fragment inside <text> is not supported";
    page("<svg t:replace=\"~{:: y}\">" + x + "</svg><b t:fragment=\"y\">y</b><div t:insert=\"~{:: x(v)}\"></div>")
        .assertError("page.html:2:62", message);
    Compilation.builder()
        .template("frags.html", "<div " + NS + "><svg t:replace=\"~{:: y}\">" + x + "</svg><b t:fragment=\"y\">y</b></div>")
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<div t:replace=\"~{frags :: x(v)}\"></div></div>")
        .compile()
        .assertError("frags.html:1:97", message);
    // An insertion point inside such a fragment is in <svg> content too.
    page("<svg t:replace=\"~{:: y}\"><g t:fragment=\"z(String w)\"><a t:insert=\"~{frags :: x(w)}\"></a></g></svg>"
            + "<b t:fragment=\"y\">y</b><svg t:insert=\"~{:: z(v)}\"></svg>")
        .assertError("page.html:2:67", "a value, condition, loop or fragment inside <a> is not supported");
    // A static fragment declared there still compiles; inserting it into an <svg> is a value inside it (#678).
    page("<svg t:replace=\"~{:: y}\"><g t:fragment=\"r(String s)\"><text>r</text></g></svg>"
            + "<b t:fragment=\"y\">y</b><svg t:insert=\"~{:: r(v)}\"></svg>")
        .assertError("page.html:2:", "inside <svg> is not supported");
  }

  @Test
  void fragmentUnderUnwrappedElementIsTheVerifiersCheck() {
    // Without its tag, a <select>'s content is parsed as HTML where it was compiled in <select>. The
    // verifier replays the hole in the state it really lands in: text in an <option>, in a <div> or
    // a <select>, which the text escaper it was compiled with covers.
    String o = "<option t:fragment=\"o(String v)\">[[v]]</option>";
    Compilation div = page("<select t:remove=\"tag\">" + o + "</select><div t:insert=\"~{:: o(v)}\"></div>").assertSuccess();
    assertEquals("<div>\n<div><option>&lt;v&gt;</option></div>\n</div>",
        div.render("page", Map.of("v", "<v>")));
    page("<select t:remove=\"tag\">" + o + "</select><select t:insert=\"~{:: o(v)}\"></select>").assertSuccess();
    page("<select t:remove=\"tag\"><option t:insert=\"~{frags :: opt(v)}\"></option></select>")
        .assertError("page.html:2:42", "fragment frags :: opt, inserted here, closes an open <option> around it");
    // On <svg>, t:remove="tag" is an error of its own.
    page("<svg t:remove=\"tag\"><g t:fragment=\"x(String s)\"><text>[[s]]</text></g></svg><div t:insert=\"~{:: x(v)}\"></div>")
        .assertError("page.html:2:", "t:remove=\"tag\" is not allowed on <svg>");
    // t:remove="tag" that keeps the context changes nothing.
    page("<div t:remove=\"tag\"><p t:insert=\"~{frags :: plain}\"></p></div>").assertSuccess();
  }

  @Test
  void rawHtmlOutsideHtmlContentIsRejected() {
    String svg = "t:utext with a SafeHtml value or HTML message is only allowed in HTML content, not in <svg> content,"
        + " where a browser parses its markup differently: write the markup in the template, or move the t:utext into"
        + " HTML content";
    raw("<svg><g t:utext=\"${h}\"></g></svg>").assertError("page.html:2:9", svg);
    raw("<svg t:utext=\"${h}\"></svg>").assertError("page.html:2:6", svg);
    raw("<svg>[(${h})]</svg>")
        .assertError("page.html:2:8", "[(...)] with a SafeHtml value or HTML message is only allowed in HTML content,"
            + " not in <svg> content");
    raw("<math><mrow t:utext=\"${h}\"></mrow></math>").assertError("page.html:2:13", "not in <math> content");
    raw("<math><mrow>[(${h})]</mrow></math>").assertError("page.html:2:", "not in <math> content");
    raw("<select><option t:utext=\"${h}\"></option></select>").assertError("page.html:2:17", "not in <select> content");
    raw("<select><option>[(${h})]</option></select>").assertError("page.html:2:", "not in <select> content");
    rawTextError(raw("<title t:utext=\"${h}\"></title>"), "page.html:2:8", "t:utext", "title");
    rawTextError(raw("<noscript><p>[(${h})]</p></noscript>"), "page.html:2:", "[(...)]", "noscript");
    raw("<select t:remove=\"tag\"><option t:utext=\"${h}\"></option></select>")
        .assertError("page.html:2:", "not in t:remove=\"tag\"");
    // A fragment under <svg t:replace> renders in <svg> content.
    raw("<svg t:replace=\"~{:: y}\"><g t:fragment=\"x\"><a t:utext=\"${h}\"></a></g></svg><b t:fragment=\"y\">y</b>")
        .assertError("page.html:2:", "not in <svg> content");
    Compilation messages =
        Compilation.builder()
            .messages("m=<p>x</p>")
            .template("page.html", "<div " + NS + ">\n<svg><g t:utext=\"#{m}\"></g>[(#{m})]</svg></div>")
            .compile();
    messages.assertError("page.html:2:9", "t:utext with a SafeHtml value or HTML message is only allowed in HTML content,"
        + " not in <svg> content");
    messages.assertError("page.html:2:", "[(...)] with a SafeHtml value or HTML message is only allowed in HTML content");
  }

  @Test
  void rawHtmlInHtmlContentStillRenders() {
    Compilation c = raw("<div t:utext=\"${h}\"></div><div>[(${h})]</div>").assertSuccess();
    assertEquals("<div>\n<div><b>h</b></div><div><b>h</b></div></div>",
        c.render("page", Map.of("h", SafeHtml.sanitize("<b>h</b>"))));
  }

  private static Compilation raw(String body) {
    return Compilation.builder()
        .template("page.html", "<div " + NS + " t:params=\"SafeHtml h\">\n" + body + "</div>")
        .compile();
  }

  @Test
  void fragmentValueInsideSvgIsRejected() {
    Compilation.builder()
        .template("box.html", "<svg " + NS + " t:fragment=\"box(Fragment content)\">\n<g t:insert=\"${content}\"></g></svg>")
        .compile()
        .assertError(
            "box.html:2:16",
            "t:insert with a Fragment value is only allowed in HTML content, not in <svg> content, where a browser parses"
                + " the fragment's markup differently: insert a ~{template :: name} declared inside <svg>");
  }

  @Test
  void fragmentArgumentDeclaredInSvgIsRejected() {
    page("<div t:replace=\"~{frags :: card(~{::a})}\"></div><svg><a><text>[[${v}]]</text></a></svg>")
        .assertError("page.html:2:67", "a value, condition, loop or fragment inside <text> is not supported");
    // The verifier replays the argument where card inserts it, in HTML content.
    page("<div t:replace=\"~{frags :: card(~{frags :: paths})}\"></div>")
        .assertError("page.html:2:17", "in fragment frags :: paths, inserted here, at frags.html:5:80, through the calls"
            + " here inserts frags :: card");
  }

  @Test
  void replacedFragmentRootIsWhereItsReplacementLands() {
    // x keeps no <svg> tag of its own: y's svg markup would land in HTML content.
    page("<svg><g t:fragment=\"y(String s)\"><text>[[s]]</text></g></svg>"
            + "<svg><svg t:fragment=\"x(String s)\" t:replace=\"~{:: y(s)}\"></svg></svg>"
            + "<div t:insert=\"~{:: x(v)}\"></div>")
        .assertError("page.html:2:42", "a value, condition, loop or fragment inside <text> is not supported");
  }

  @Test
  void breakoutsAndComputedEncodingsInForeignContentAreErrors() {
    // #678: the parser rejects a breakout tag in <svg>/<math>, and nothing inside them is computed,
    // so no fragment is ever in or holds mixed foreign content.
    String breakout = "inside <svg> or <math> is HTML in a browser and ends the <svg> or <math> there";
    page("<svg><p>x</p><g t:fragment=\"m\"><path d=\"M0 0\"/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:6", breakout);
    page("<svg><font COLOR=\"red\">x</font><g t:fragment=\"m\"><path/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:6", breakout);
    page("<svg><font t:color=\"v\">x</font><g t:fragment=\"m\"><path/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:6", breakout);
    page("<math><annotation-xml t:encoding=\"'text/html'\"><t:block t:replace=\"~{::payload(v)}\"></t:block></annotation-xml>"
            + "<annotation-xml><mrow t:fragment=\"payload(String s)\"><mi>x</mi></mrow></annotation-xml></math>")
        .assertError("page.html:2:23", "t:encoding is not allowed");
    page("<math><annotation-xml encoding=\"text/html\" t:attr=\"id=${v}, encoding=${v}\"><t:block"
            + " t:replace=\"~{::payload(v)}\"></t:block></annotation-xml><annotation-xml><mrow t:fragment=\"payload(String s)\">"
            + "[[s]]</mrow></annotation-xml></math>")
        .assertError("page.html:2:", "inside <math> is not supported");
  }

  @Test
  void selectHoldsOnlyOptionsGroupsAndSeparators() {
    // The verifier's SELECT_CONTENT checks what each call really puts into a <select>.
    String fix = "in a <select>: browsers that parse <select> the classic way";
    page("<select><option t:insert=\"~{frags :: sel(v)}\"></option></select>")
        .assertError("page.html:2:27", "in fragment frags :: sel, inserted here, at frags.html:7:1: <span> " + fix);
    // Its own template: no template could insert it anywhere, so the verifier rejects it on its own
    // where nothing calls it (#461).
    Compilation.builder()
        .template("nest.html", "<div " + NS + "><i t:fragment=\"plain\">plain</i>"
            + "<option t:fragment=\"nest\"><b t:replace=\"~{:: plain}\"></b></option></div>")
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<select t:insert=\"~{nest :: nest}\"></select></div>")
        .compile()
        .assertError("page.html:2:19", fix);
    // A SafeHtml could hold a <style> that a <select> ignores.
    Compilation raw =
        Compilation.builder()
            .template("frags.html", "<div " + NS + ">\n<option t:fragment=\"o(SafeHtml h)\" t:utext=\"${h}\"></option>\n"
                + "<option t:fragment=\"p(SafeHtml h)\"><b>[(${h})]</b></option></div>")
            .template("page.html", "<div " + NS + " t:params=\"SafeHtml h\">\n<select t:insert=\"~{frags :: o(h)}\">"
                + "</select><select t:insert=\"~{frags :: p(h)}\"></select></div>")
            .compile();
    raw.assertError("page.html:2:19", "in fragment frags :: o, inserted here, at frags.html:2:47: a browser lets the"
        + " markup of this SafeHtml value close an open <select>");
    raw.assertError("page.html:2:64", "in fragment frags :: p, inserted here, at frags.html:3:36: <b> " + fix);
    Compilation.builder()
        .template("frags.html", "<div " + NS + ">\n<option t:fragment=\"o(String s)\">[[${s}]]</option></div>")
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<select t:insert=\"~{frags :: o(v)}\"></select></div>")
        .compile()
        .assertSuccess();
  }

  @Test
  void selectContentIsCheckedOnTheComposedPage() {
    // Firefox ESR ignores other start tags in a select: the <script> in the title runs, and the
    // hole after the select lands in the <script> that the ignored <style> hid. The verifier rejects
    // each at the tag (HtmlParser no longer checks it on its own).
    String fix = "in a <select>: browsers that parse <select> the classic way";
    selectPage("<select><title><script>[[${x}]]</script></title></select>").assertError("t.html:2:9", "<title> " + fix);
    selectPage("<SELECT>\n<option>a<STYLE><script></STYLE></option></SELECT>[[${x}]]")
        .assertError("t.html:3:10", "<style> " + fix);
    selectPage("<select><option><svg><title>t</title></svg></option></select>")
        .assertError("t.html:2:17", "<svg> " + fix);
    for (String tag : List.of("math", "button", "div", "noscript", "textarea", "script", "template")) {
      selectPage("<select><" + tag + "></" + tag + "></select>").assertError("t.html:2:9", "<" + tag + "> " + fix);
    }
    selectPage("<select><input></select>").assertError("t.html:2:9", "[verifier:PARSE_ERROR]");
    selectPage("<select><option><select></select></option></select>").assertError("t.html:2:17", "[verifier:PARSE_ERROR]");
    // Without its tag the select is gone: the static <title> is in HTML content, where it parses cleanly.
    selectPage("<select t:remove=\"tag\"><title>t</title></select>").assertSuccess();
    selectPage("<select><option>a</option><optgroup label=\"g\"><option>b</option></optgroup><hr>"
            + "<t:block t:each=\"o : ${os}\"><option t:text=\"${o}\">o</option></t:block><!-- c --> [[${x}]] </select>")
        .assertSuccess();
  }

  private static Compilation selectPage(String body) {
    return Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String x, java.util.List<String> os\">\n" + body + "</div>")
        .compile();
  }

  @Test
  void fragmentDeclaredInAnHtmlIntegrationPointIsStaticMarkup() {
    // Nothing inside <math> is an insertion point (#678), so a fragment declared in an
    // annotation-xml holds no hole: inserting its static markup in HTML content is the author's own.
    page("<math><annotation-xml encoding=\"text/html\"><div t:fragment=\"h\">x</div></annotation-xml></math>"
            + "<div t:insert=\"~{:: h}\"></div>")
        .assertSuccess();
    page("<math><annotation-xml encoding=\"text/html\"><div t:fragment=\"h\">x</div></annotation-xml></math>"
            + "<math><mrow t:insert=\"~{:: h}\"></mrow></math>")
        .assertError("page.html:2:", "a value, condition, loop or fragment inside <math> is not supported");
  }

  @Test
  void annotationXmlEncodingNameFoldsOnlyAscii() {
    // encodıng and encodİng are not encoding to a browser, so the annotation-xml holds MathML: there
    // <title> holds markup and <mi> holds HTML, so the HTML fragment's RCDATA <script> would run s.
    for (String name : List.of("encodıng", "encodİng")) {
      Compilation.builder()
          .template("frags.html", "<div " + NS + ">\n<a t:fragment=\"m(String s)\"><title><mi><script>[[s]]</script>"
              + "</mi></title></a></div>")
          .template("page.html", "<div " + NS + " t:params=\"String v\">\n<math><annotation-xml " + name
              + "=\"text/html\" t:insert=\"~{frags :: m(v)}\"></annotation-xml></math></div>")
          .compile()
          .assertError("page.html:2:54", "a value, condition, loop or fragment inside <math> is not supported");
    }
  }

  @Test
  void fragmentsInTheContextTheyAreDeclaredInStillRender() {
    Compilation c =
        page("<svg t:replace=\"~{frags :: check}\"></svg>"
                + "<select><option t:replace=\"~{frags :: opt(v)}\"></option></select><select t:insert=\"~{frags :: opts}\"></select>"
                + "<div t:replace=\"~{frags :: card(~{::b})}\"></div><template><b>[[${v}]]</b></template>"
                + "<title t:replace=\"~{frags :: plain}\"></title>")
            .assertSuccess();
    assertEquals(
        "<div>\n"
            + "<svg viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg>"
            + "<select><option>&lt;v&gt;</option></select><select><option>a</option></select>"
            + "<section><b>&lt;v&gt;</b></section><template><b>&lt;v&gt;</b></template>"
            + "<i>plain</i>\n"
            + "</div>",
        c.render("page", Map.of("v", "<v>")));
    // #678: a fragment inserted inside an <svg> or <math> is a value there.
    for (String inside : new String[] {"<svg t:insert=\"~{frags :: paths}\"></svg>",
        "<svg><g t:replace=\"~{frags :: check}\"></g></svg>", "<svg><t:block t:insert=\"~{frags :: paths}\"></t:block></svg>",
        "<svg><foreignObject><p t:insert=\"~{frags :: plain}\"></p></foreignObject></svg>",
        "<math><mi t:replace=\"~{frags :: row}\"></mi></math>"}) {
      page(inside).assertError("page.html:2:", "is not supported", "as static markup");
    }
  }
}
