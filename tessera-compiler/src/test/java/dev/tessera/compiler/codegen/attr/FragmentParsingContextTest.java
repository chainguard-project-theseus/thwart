package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.SafeHtml;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A fragment is compiled in the parsing context it is declared in (HTML, {@code <svg>},
 * {@code <math>}, raw text), so t:insert and t:replace may only put it where a browser parses
 * its markup the same way. In {@code <svg>}, {@code <title>} holds HTML, so an HTML fragment's
 * {@code <title><script>[[s]]</script></title>} would run {@code s} as script there.
 */
class FragmentParsingContextTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static final String FRAGS =
      "<div " + NS + ">\n"
          + "<a t:fragment=\"x(String s)\"><title><script>[[s]]</script></title></a>\n"
          + "<i t:fragment=\"plain\">plain</i>\n"
          + "<svg t:fragment=\"check\" viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg>\n"
          + "<svg><g t:fragment=\"r(String s)\"><text>[[s]]</text></g>"
          + "<t:block t:fragment=\"paths\"><path d=\"M0 0\"/></t:block></svg>\n"
          + "<math><mrow t:fragment=\"row\"><mi>x</mi></mrow></math>\n"
          + "<span t:fragment=\"sel(String s)\"><style><script></style>[[s]]</span>\n"
          + "<option t:fragment=\"opt(String s)\" t:text=\"s\">x</option><t:block t:fragment=\"opts\"><option>a</option></t:block>\n"
          + "<option t:fragment=\"nest\"><b t:replace=\"~{:: plain}\"></b></option>\n"
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
    String message = "fragment 'frags :: x' is declared in HTML content but t:insert puts it in <svg> content, where a"
        + " browser parses its markup differently: insert it in HTML content, or declare the fragment inside <svg>"
        + " in its template";
    page("<svg><g t:insert=\"~{frags :: x(v)}\"></g></svg>").assertError("page.html:2:19", message);
    page("<svg t:insert=\"~{frags :: x(v)}\"></svg>").assertError("page.html:2:16", message);
    page("<svg><g t:replace=\"~{frags :: x(v)}\"></g></svg>")
        .assertError("page.html:2:20", "fragment 'frags :: x' is declared in HTML content but t:replace puts it in <svg>");
    page("<math><mrow t:insert=\"~{frags :: plain}\"></mrow></math>")
        .assertError("page.html:2:23", "fragment 'frags :: plain' is declared in HTML content but t:insert puts it in"
            + " <math> content");
    page("<math><mrow t:insert=\"~{frags :: check}\"></mrow></math>")
        .assertError("page.html:2:23", "fragment 'frags :: check' is declared in HTML content but t:insert puts it in"
            + " <math> content");
    page("<math><mi t:insert=\"~{frags :: row}\"></mi></math>")
        .assertError("page.html:2:21", "fragment 'frags :: row' is declared in <math> content but t:insert puts it in"
            + " HTML content");
  }

  @Test
  void htmlTitleFragmentReplacingInsideSvgIsRejected() {
    // An SVG <title> holds HTML, so the RCDATA-escaped hole would run inside the <script>.
    Compilation.builder()
        .template("frag.html", "<title " + NS + " t:fragment=\"payload(String x)\"><script>[[x]]//</script></title>")
        .template("page.html", "<div " + NS + " t:params=\"String x\">\n<svg><g t:replace=\"~{frag :: payload(x)}\"></g></svg></div>")
        .compile()
        .assertError("page.html:2:20", "fragment 'frag :: payload' is declared in HTML content but t:replace puts it in"
            + " <svg> content");
    page("<svg><g t:replace=\"~{::f(v)}\"></g></svg><title t:fragment=\"f(String s)\"><script>[[s]]</script></title>")
        .assertError("page.html:2:20", "fragment 'page :: f' is declared in HTML content but t:replace puts it in <svg>");
  }

  @Test
  void svgFragmentInsideHtmlIsRejected() {
    page("<div t:insert=\"~{frags :: r(v)}\"></div>")
        .assertError(
            "page.html:2:16",
            "fragment 'frags :: r' is declared in <svg> content but t:insert puts it in HTML content, where a browser"
                + " parses its markup differently: insert it in <svg> content, or declare the fragment outside <svg>");
    page("<p t:replace=\"~{frags :: paths}\"></p>")
        .assertError("page.html:2:15", "fragment 'frags :: paths' is declared in <svg> content but t:replace puts it in"
            + " HTML content");
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
    // A fragment declared in raw text fits nowhere, so it never renders where the raw-text rule does not look.
    page("<noscript><b t:fragment=\"n\">x</b></noscript><div t:insert=\"~{:: n}\"></div>")
        .assertError("page.html:2:", "fragment 'page :: n' is declared in <noscript> content but t:insert puts it in HTML"
            + " content, where a browser parses its markup differently: declare the fragment outside <noscript>");
    page("<svg><noscript><g t:fragment=\"n\"><path/></g></noscript></svg><svg t:insert=\"~{:: n}\"></svg>")
        .assertError("page.html:2:", "fragment ~{::n} is in or holds an <svg> or <math>");
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
    // they fit only in <svg> content.
    String x = "<g t:fragment=\"x(String v)\"><text>[[v]]</text></g>";
    String message = "fragment 'page :: x' is declared in <svg> content but t:insert puts it in HTML content";
    page("<svg t:replace=\"~{:: y}\">" + x + "</svg><b t:fragment=\"y\">y</b><div t:insert=\"~{:: x(v)}\"></div>")
        .assertError("page.html:2:", message);
    Compilation.builder()
        .template("frags.html", "<div " + NS + "><svg t:replace=\"~{:: y}\">" + x + "</svg><b t:fragment=\"y\">y</b></div>")
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<div t:replace=\"~{frags :: x(v)}\"></div></div>")
        .compile()
        .assertError("page.html:2:", "fragment 'frags :: x' is declared in <svg> content but t:replace puts it in HTML"
            + " content");
    // An insertion point inside such a fragment is in <svg> content too.
    page("<svg t:replace=\"~{:: y}\"><g t:fragment=\"z(String w)\"><a t:insert=\"~{frags :: x(w)}\"></a></g></svg>"
            + "<b t:fragment=\"y\">y</b><svg t:insert=\"~{:: z(v)}\"></svg>")
        .assertError("page.html:2:", "fragment 'frags :: x' is declared in HTML content but t:insert puts it in <svg>"
            + " content");
    // The fragment still fits in <svg> content, where it was compiled.
    page("<svg t:replace=\"~{:: y}\"><g t:fragment=\"r(String s)\"><text>[[s]]</text></g></svg>"
            + "<b t:fragment=\"y\">y</b><svg t:insert=\"~{:: r(v)}\"></svg>")
        .assertSuccess();
  }

  @Test
  void fragmentUnderUnwrappedElementFitsNowhere() {
    // Without its tag, a <select>'s content is parsed as HTML where it was compiled in <select>.
    String o = "<option t:fragment=\"o(String v)\">[[v]]</option>";
    String message = "fragment ~{::o} is in or is an element with t:remove=\"tag\" whose tag changes how its content is"
        + " parsed, so it is compiled for one context and rendered in another and fits nowhere: drop the"
        + " t:remove=\"tag\", or move the fragment out of that element";
    page("<select t:remove=\"tag\">" + o + "</select><div t:insert=\"~{:: o(v)}\"></div>").assertError("page.html:2:", message);
    page("<select t:remove=\"tag\">" + o + "</select><select t:insert=\"~{:: o(v)}\"></select>")
        .assertError("page.html:2:", message);
    page("<select t:remove=\"tag\"><option t:insert=\"~{frags :: opt(v)}\"></option></select>")
        .assertError("page.html:2:", "fragment 'frags :: opt' is declared in HTML content but t:insert puts it in"
            + " t:remove=\"tag\" (on an element whose tag changes how its content is parsed) content, where a browser"
            + " parses its markup differently: no fragment fits there: drop the t:remove=\"tag\"");
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
    Compilation c =
        // Not directly in MathML <mi>: the verifier takes HTML only where the current node is HTML (#367).
        raw("<div t:utext=\"${h}\"></div><div>[(${h})]</div><svg><foreignObject><div t:utext=\"${h}\"></div></foreignObject>"
                + "</svg><math><mi><span>[(${h})]</span></mi></math>")
            .assertSuccess();
    assertEquals(
        "<div>\n<div><b>h</b></div><div><b>h</b></div><svg><foreignObject><div><b>h</b></div></foreignObject></svg>"
            + "<math><mi><span><b>h</b></span></mi></math></div>",
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
        .assertError(
            "page.html:2:33",
            "fragment argument ~{::a} is declared in <svg> content, but a Fragment value is inserted in HTML content,"
                + " where a browser parses its markup differently: pass a fragment or element declared outside <svg>");
    page("<div t:replace=\"~{frags :: card(~{frags :: paths})}\"></div>")
        .assertError("page.html:2:33", "fragment argument ~{frags :: paths} is declared in <svg> content");
  }

  @Test
  void replacedFragmentRootIsWhereItsReplacementLands() {
    // x keeps no <svg> tag of its own: y's svg markup would land in HTML content.
    page("<svg><g t:fragment=\"y(String s)\"><text>[[s]]</text></g></svg>"
            + "<svg><svg t:fragment=\"x(String s)\" t:replace=\"~{:: y(s)}\"></svg></svg>"
            + "<div t:insert=\"~{:: x(v)}\"></div>")
        .assertError("page.html:2:", "fragment 'page :: x' is declared in <svg> content but t:insert puts it in HTML content");
  }

  @Test
  void svgWithABreakoutTagFitsNothing() {
    String mixed = "mixed <svg>/<math> (one holding an HTML element such as <p>)";
    page("<svg><p>x</p><g t:fragment=\"m\"><path d=\"M0 0\"/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:", "fragment ~{::m} is in or holds an <svg> or <math> with an HTML element such as <p>"
            + " in it, where a browser leaves foreign content early, so it fits nowhere");
    page("<svg><font COLOR=\"red\">x</font><g t:fragment=\"m\"><path/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:", "fragment ~{::m} is in or holds an <svg>");
    page("<svg><font t:color=\"v\">x</font><g t:fragment=\"m\"><path/></g></svg><svg t:insert=\"~{:: m}\"></svg>")
        .assertError("page.html:2:", "fragment ~{::m} is in or holds an <svg>");
    page("<svg><p>x</p><g t:insert=\"~{frags :: paths}\"></g></svg>")
        .assertError("page.html:2:", "fragment 'frags :: paths' is declared in <svg> content but t:insert puts it in "
            + mixed + " content, where a browser parses its markup differently: no fragment fits in mixed content:"
            + " move the HTML element out of the <svg> or <math> or into a <foreignObject>");
  }

  @Test
  void computedAnnotationXmlEncodingFitsNothing() {
    page("<math><annotation-xml t:encoding=\"'text/html'\"><t:block t:replace=\"~{::payload(v)}\"></t:block></annotation-xml>"
            + "<annotation-xml><mrow t:fragment=\"payload(String s)\"><svg><title><script>[[s]]</script></title></svg></mrow>"
            + "</annotation-xml></math>")
        .assertError("page.html:2:", "fragment 'page :: payload' is declared in <annotation-xml> content but t:replace puts"
            + " it in mixed <svg>/<math>");
  }

  @Test
  void selectHoldsOnlyFragmentsWithoutTitleStyleOrInsertions() {
    String fix = "insert it in HTML content, or keep <title>, <style>, <svg>, <math>, t:insert, t:replace, t:utext and"
        + " [(...)] out of the fragment, since some browsers ignore those tags in a <select> and parse their content as"
        + " markup";
    page("<select><option t:insert=\"~{frags :: sel(v)}\"></option></select>")
        .assertError("page.html:2:27", "fragment 'frags :: sel' is declared in HTML content but t:insert puts it in <select>"
            + " content, where a browser parses its markup differently: " + fix);
    page("<select t:insert=\"~{frags :: nest}\"></select>").assertError("page.html:2:19", fix);
    // A SafeHtml could hold a <style> that a <select> ignores.
    Compilation raw =
        Compilation.builder()
            .template("frags.html", "<div " + NS + ">\n<option t:fragment=\"o(SafeHtml h)\" t:utext=\"${h}\"></option>\n"
                + "<option t:fragment=\"p(SafeHtml h)\"><b>[(${h})]</b></option></div>")
            .template("page.html", "<div " + NS + " t:params=\"SafeHtml h\">\n<select t:insert=\"~{frags :: o(h)}\">"
                + "</select><select t:insert=\"~{frags :: p(h)}\"></select></div>")
            .compile();
    raw.assertError("page.html:2:19", "fragment 'frags :: o' is declared in HTML content but t:insert puts it in <select>");
    raw.assertError("page.html:2:", "fragment 'frags :: p' is declared in HTML content but t:insert puts it in <select>");
    Compilation.builder()
        .template("frags.html", "<div " + NS + ">\n<option t:fragment=\"o(String s)\">[[${s}]]</option></div>")
        .template("page.html", "<div " + NS + " t:params=\"String v\">\n<select t:insert=\"~{frags :: o(v)}\"></select></div>")
        .compile()
        .assertSuccess();
  }

  @Test
  void htmlAnnotationXmlHoldsHtml() {
    page("<math><annotation-xml encoding=\"text/html\"><div t:fragment=\"h\">x</div></annotation-xml></math>"
            + "<math><mrow t:insert=\"~{:: h}\"></mrow></math>")
        .assertError("page.html:2:", "fragment 'page :: h' is declared in HTML content but t:insert puts it in <math>"
            + " content");
  }

  @Test
  void fragmentsInTheContextTheyAreDeclaredInStillRender() {
    Compilation c =
        page("<svg t:replace=\"~{frags :: check}\"></svg>"
                + "<svg t:insert=\"~{frags :: paths}\"></svg>"
                + "<svg class=\"icon\" t:insert=\"~{frags :: check}\"><g>preview</g></svg>"
                + "<select><option t:replace=\"~{frags :: opt(v)}\"></option></select><select t:insert=\"~{frags :: opts}\"></select>"
                + "<svg><g t:replace=\"~{frags :: check}\"></g></svg>"
                + "<svg><g t:replace=\"~{frags :: r(v)}\"></g></svg>"
                + "<svg><t:block t:insert=\"~{frags :: paths}\"></t:block></svg>"
                + "<svg><foreignObject><p t:insert=\"~{frags :: plain}\"></p></foreignObject></svg>"
                + "<math><mi t:replace=\"~{frags :: row}\"></mi></math>"
                + "<div t:replace=\"~{frags :: card(~{::b})}\"></div><template><b>[[${v}]]</b></template>"
                + "<title t:replace=\"~{frags :: plain}\"></title>")
            .assertSuccess();
    assertEquals(
        "<div>\n"
            + "<svg viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg>"
            + "<svg><path d=\"M0 0\"/></svg>"
            + "<svg class=\"icon\"><svg viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg></svg>"
            + "<select><option>&lt;v&gt;</option></select><select><option>a</option></select>"
            + "<svg><svg viewBox=\"0 0 1 1\"><title>Check</title><path d=\"M0 0\"/></svg></svg>"
            + "<svg><g><text>&lt;v&gt;</text></g></svg>"
            + "<svg><path d=\"M0 0\"/></svg>"
            + "<svg><foreignObject><p><i>plain</i></p></foreignObject></svg>"
            + "<math><mrow><mi>x</mi></mrow></math>"
            + "<section><b>&lt;v&gt;</b></section><template><b>&lt;v&gt;</b></template>"
            + "<i>plain</i>\n"
            + "</div>",
        c.render("page", Map.of("v", "<v>")));
  }
}
