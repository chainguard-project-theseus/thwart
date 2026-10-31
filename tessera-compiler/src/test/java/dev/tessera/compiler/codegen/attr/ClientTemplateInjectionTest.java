package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Client-side template injection (#248): Vue, petite-vue and AngularJS compile the rendered DOM
 * as a template, so {@code {{ }}} in an escaped value would run as an expression. Element text
 * breaks every {@code {{} with an empty comment; {@code <title>}/{@code <textarea>} (RCDATA),
 * attributes and scripts are unchanged; an expression inside a static {@code {{ }}} is a build
 * error.
 */
class ClientTemplateInjectionTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\""
          + " t:params=\"String s, String a, String b, SafeHtml h\">";
  private static final String PAYLOAD = "{{ constructor.constructor('alert(1)')() }}";
  private static final String BROKEN = "<!---->{<!---->{ constructor.constructor(&#39;alert(1)&#39;)() }}";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, String s) {
    return render(body, s, "", "");
  }

  private static String render(String body, String s, String a, String b) {
    String out =
        compile(body).assertSuccess().render("t", Map.of("s", s, "a", a, "b", b, "h", dev.tessera.runtime.SafeHtml.EMPTY));
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void issueReproductionIsBroken() {
    assertEquals(
        "<div id=\"app\" v-cloak>\n  <p>" + BROKEN + "</p>\n</div>",
        render("<div id=\"app\" v-cloak>\n  <p t:text=\"s\">...</p>\n</div>", PAYLOAD));
    assertEquals("<p>a " + BROKEN + " b</p>", render("<p>a [[s]] b</p>", PAYLOAD));
    // No directive needed: the mount point may be chosen in JavaScript or in a layout.
    assertEquals("<span>" + BROKEN + "</span>", render("<span t:text=\"s\">x</span>", PAYLOAD));
  }

  @Test
  void bracesNeverMeetAcrossStaticTextOrValues() {
    // static "{" then a value starting with "{"
    assertEquals("<p>{<!---->{x}}</p>", render("<p>{[[s]]</p>", "{x}}"));
    // a value ending with "{" then static "{"
    assertEquals("<p>a{<!---->{x}}</p>", render("<p>[[s]]{x}}</p>", "a{"));
    // two adjacent values
    assertEquals("<p><!---->{<!----><!---->{x}}</p>", render("<p>[[a]][[b]]</p>", "", "{", "{x}}"));
    assertEquals("<p>{<!---->{<!---->{x}}}</p>", render("<p>{[[s]]</p>", "{{x}}}"));
  }

  @Test
  void valuesWithoutBracesAreUnchanged() {
    assertEquals("<p>a &amp; b }} {</p>", render("<p>[[s]] {</p>", "a & b }}"));
    assertEquals("<p>{{ static }}</p>", render("<p>{{ static }}</p>", ""));
  }

  @Test
  void textInOtherHtmlElementsIsBroken() {
    // Comments are harmless in options, SVG and MathML text, and in raw-text fallbacks.
    assertEquals("<select><option><!---->{<!---->{x}}</option></select>",
        render("<select><option t:text=\"s\">o</option></select>", "{{x}}"));
    assertEquals("<svg><text><!---->{<!---->{x}}</text><desc><!---->{<!---->{x}}</desc></svg>",
        render("<svg><text t:text=\"s\">t</text><desc>[[s]]</desc></svg>", "{{x}}"));
    assertEquals("<math><mi><!---->{<!---->{x}}</mi></math>", render("<math><mi t:text=\"s\">x</mi></math>", "{{x}}"));
    assertEquals("<template><p><!---->{<!---->{x}}</p></template>",
        render("<template><p t:text=\"s\">x</p></template>", "{{x}}"));
    // Inside svg and math, <title> and <textarea> are not RCDATA: a comment is a real node there (#277).
    assertEquals("<svg><title><!---->{<!---->{x}}</title><title>a <!---->{<!---->{x}}</title></svg>",
        render("<svg><title t:text=\"s\">t</title><title>a [[s]]</title></svg>", "{{x}}"));
    assertEquals("<math><textarea><!---->{<!---->{x}}</textarea></math>",
        render("<math><textarea>[[s]]</textarea></math>", "{{x}}"));
  }

  @Test
  void titleAndTextareaAreNotChanged() {
    // RCDATA: a comment would show as text there (guide section 7).
    assertEquals("<title>{{x}} &amp;</title>", render("<title t:text=\"s\">t</title>", "{{x}} &"));
    assertEquals("<title>a {{x}} &amp;</title>", render("<title>a [[s]]</title>", "{{x}} &"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea t:text=\"s\"></textarea>", "{{x}}"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea>[[s]]</textarea>", "{{x}}"));
    assertEquals("<TITLE>{{x}}</TITLE>", render("<TITLE t:text=\"s\">t</TITLE>", "{{x}}"));
    // An <annotation-xml> whose t:attr names only other attributes keeps its HTML encoding: an HTML title.
    assertEquals("<math><annotation-xml encoding=\"text/html\" id=\"{{x}}\"><title>{{x}}</title></annotation-xml></math>",
        render("<math><annotation-xml encoding=\"text/html\" t:attr=\"id=s\"><title>[[s]]</title></annotation-xml></math>",
            "{{x}}"));
  }

  @Test
  void attributesAndScriptsAreNotChanged() {
    assertEquals("<i title=\"{{x}}\" data-v=\"{{x}}\">i</i>", render("<i t:title=\"s\" t:data-v=\"s\">i</i>", "{{x}}"));
    assertEquals("<a href=\"{{x}}\">a</a>", render("<a t:href=\"s\">a</a>", "{{x}}"));
    // petite-vue interpolates script text: a JSON string writes '{' as \u007b
    assertEquals("<script>var v = (\"\\u007b\\u007bx}}\");</script>", render("<script>var v = [[s]];</script>", "{{x}}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ greet('[[s]]') }}</p>",
        "<p>{{ f([[s]]) }}</p>",
        "<p>{{[[s]]}}</p>",
        "<p>{{ a }} {{ b + [[s]] }}</p>",
        "<p>&#123;&#123; [[s]] }}</p>",
        "<p>&#x7B;&lbrace; [[s]] }}</p>",
        "<p>{{ [(h)] }}</p>",
        "<title>{{ [[s]] }}</title>",
        "<textarea>{{ [[s]] }}</textarea>",
        "<svg><title>{{ [[s]] }}</title></svg>",
      })
  void expressionInsideStaticMustacheIsABuildError(String body) {
    compile(body).assertError("t.html:1:", "inside a client-side {{ ... }} interpolation", "data-*");
  }

  @Test
  void mustacheErrorNamesTheExpressionPosition() {
    compile("\n<p>\n  {{ greet('[[s]]') }}</p>").assertError("t.html:3:15:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ a }} [[s]] {{ b }}</p>",
        "<p>}} [[s]]</p>",
        "<p>{ { [[s]] }}</p>",
        "<p>{{ x &#125;} [[s]]</p>",
        "<p>&amp;#123;&amp;#123; [[s]]</p>",
        "<script>var v = [[s]]; var t = '{{}}';</script>",
        "<p>{{ a }}<b t:text=\"s\">x</b></p>",
      })
  void expressionOutsideStaticMustacheCompiles(String body) {
    compile(body).assertSuccess();
  }

  /**
   * #348: a browser moves text out of {@code table}, {@code tbody}, {@code thead}, {@code tfoot},
   * {@code tr} and {@code colgroup} in front of the table but keeps comments inside it, so the
   * braces of a broken {@code {{} join up again.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<table>[[s]]<tr><td>x</td></tr></table>",
        "<table><tr><td>x</td></tr><tr t:text=\"s\"></tr></table>",
        "<table><tbody>[[s]]<tr><td>x</td></tr></tbody></table>",
        "<table><thead>x [[s]]</thead></table>",
        "<table><tfoot t:text=\"s\"></tfoot></table>",
        "<table><tr>[[s]]<td>x</td></tr></table>",
        "<TABLE><TR>[[s]]</TR></TABLE>",
        "<table><colgroup>[[s]]</colgroup></table>",
        "<table><t:block>[[s]]</t:block></table>",
        "<table><t:block t:text=\"s\"></t:block></table>",
        "<table><tr><span t:remove=\"tag\">[[s]]</span></tr></table>",
        "<table><t:block t:if=\"${s != null}\"><t:block>[[s]]</t:block></t:block></table>",
        "<table><tr t:utext=\"h\"></tr></table>",
        "<table><tr>[(h)]</tr></table>",
        "<svg><table>[[s]]</table></svg>",
      })
  void computedTextDirectlyInTableIsABuildError(String body) {
    Compilation c = compile("\n" + body);
    c.assertError("t.html:2:", "directly inside <", "put the value in a <td>, <th> or <caption>");
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  @Test
  void tableTextErrorNamesThePosition() {
    compile("\n<table>\n  <tr>[[s]]</tr></table>")
        .assertError(
            "t.html:3:9:",
            "[[...]] directly inside <tr> is not supported: a browser moves the text in front of the table but"
                + " leaves the comments that break {{ in the value inside it");
    compile("\n<table><tbody>\n <t:block t:text=\"s\"/></tbody></table>")
        .assertError("t.html:3:11:", "t:text directly inside <tbody> is not supported");
  }

  @Test
  void textInTableCellsIsBroken() {
    assertEquals(
        "<table><caption>" + BROKEN + "</caption><tr><td>" + BROKEN + "</td><th>" + BROKEN + "</th></tr></table>",
        render("<table><caption>[[s]]</caption><tr><td>[[s]]</td><th t:text=\"s\">x</th></tr></table>", PAYLOAD));
    assertEquals(
        "<table><tr><td><table><tr><td>" + BROKEN + "</td></tr></table><p>" + BROKEN + "</p>" + BROKEN
            + "</td></tr></table>",
        render(
            "<table><tr><td><table><tr><td>[[s]]</td></tr></table><p>[[s]]</p><t:block>[[s]]</t:block></td></tr></table>",
            PAYLOAD));
    assertEquals(
        "<table>\n  <tr>\n    <td>x</td>\n  </tr>\n</table>" + BROKEN,
        render("<table>\n  <tr>\n    <td>x</td>\n  </tr>\n</table>[[s]]", PAYLOAD));
    // Without its tag the table is not there, so the text is ordinary element text.
    assertEquals(BROKEN, render("<table t:remove=\"tag\">[[s]]</table>", PAYLOAD));
  }

  /**
   * #348: a table part where a browser does not keep it makes the browser close elements up to
   * one that takes it (a {@code <tr>} ends a {@code <td>}), so the text after it is read in the
   * table and moved in front of it. A {@code <form>} in a table is closed at once.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<table><tr><td><tr></tr>[[s]]</td></tr></table>",
        "<table><tr><td><p><tr></tr>[[s]]</p></td></tr></table>",
        "<table><tr><td><select><tr></tr>[[s]]</select></td></tr></table>",
        "<table><tr><td><tbody></tbody>[[s]]</td></tr></table>",
        "<table><tr><td><th></th>[[s]]</td></tr></table>",
        "<table><tr><td><col>[[s]]</td></tr></table>",
        "<table><caption><tr></tr>[[s]]</caption></table>",
        "<table><caption><b><td></td>[[s]]</b></caption></table>",
        "<table><div><tbody></tbody>[[s]]</div></table>",
        "<table><b><tr></tr>[[s]]</b></table>",
        "<table><form>[[s]]</form></table>",
        "<table><tr><form>[[s]]</form></tr></table>",
      })
  void misplacedTablePartIsABuildError(String body) {
    Compilation c = compile("\n" + body);
    c.assertError("t.html:2:", "is not supported: a browser would close elements to place it");
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  private static final String FRAGMENTS =
      "<div xmlns:t=\"https://tessera.dev\">"
          + "<t:block t:fragment=\"text(String s)\">[[${s}]]</t:block>"
          + "<b t:fragment=\"tagless(String s)\" t:remove=\"tag\">[[${s}]]</b>"
          + "<t:block t:fragment=\"nested(String s)\" t:insert=\"~{::text(${s})}\"></t:block>"
          + "<tr t:fragment=\"row(String s)\"><td>[[${s}]]</td></tr>"
          + "<t:block t:fragment=\"rows(String s)\"><tr><td>[[${s}]]</td></tr><tr t:replace=\"~{::row(${s})}\"></tr></t:block>"
          + "<div t:fragment=\"box(String s)\">[[${s}]]</div>"
          + "<form t:fragment=\"form(String s)\">[[${s}]]</form>"
          + "<div t:fragment=\"layout(Fragment body)\"><main t:insert=\"${body}\"></main><table><tr>"
          + "<td t:insert=\"${body}\"></td></tr></table></div>"
          + "</div>";

  private static Compilation compileWithFragments(String body) {
    return Compilation.builder()
        .template("t.html", HEADER + body + "</div>")
        .template("f.html", FRAGMENTS)
        .compile();
  }

  /** #348: what t:insert/t:replace writes where it lands, through tagless fragment roots. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<table><tbody t:insert=\"~{f :: text(s)}\"></tbody></table>",
        "<table><tr t:replace=\"~{f :: text(s)}\"></tr></table>",
        "<table><tr t:replace=\"~{f :: tagless(s)}\"></tr></table>",
        "<table><t:block t:insert=\"~{f :: nested(s)}\"></t:block></table>",
        "<table><tr><td t:insert=\"~{f :: row(s)}\"></td></tr></table>",
        "<table><tr><td><b t:replace=\"~{f :: row(s)}\"></b>[[s]]</td></tr></table>",
        "<table><tbody t:insert=\"~{f :: form(s)}\"></tbody></table>",
        "<t:block t:replace=\"~{f :: layout(~{::tr})}\"></t:block><table><tr><td t:text=\"s\">x</td></tr></table>",
        // Rows in a <div>: a browser drops the <tr> and <td> tags, a parse error (#406).
        "<div t:insert=\"~{f :: rows(s)}\"></div>",
      })
  void insertedTextOrTablePartInTableIsABuildError(String body) {
    Compilation c = compileWithFragments("\n" + body);
    assertEquals(false, c.success(), body);
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  @Test
  void fragmentValueDirectlyInTableIsABuildError() {
    Compilation.builder()
        .template(
            "f.html",
            "<div xmlns:t=\"https://tessera.dev\" t:fragment=\"slot(Fragment body)\">\n<table><tbody"
                + " t:insert=\"${body}\"></tbody></table></div>")
        .compile()
        .assertError("f.html:2:15:", "t:insert writes computed text or a Fragment value directly inside <tbody>");
  }

  @Test
  void insertedTableContentCompiles() {
    compileWithFragments(
            "<table><tbody t:insert=\"~{f :: rows(s)}\"></tbody>"
                + "<tbody><tr t:replace=\"~{f :: row(s)}\"></tr><t:block t:replace=\"~{f :: rows(s)}\"></t:block></tbody>"
                + "<tr><td t:insert=\"~{f :: box(s)}\"></td><td t:insert=\"~{f :: text(s)}\"></td></tr></table>"
                + "<t:block t:replace=\"~{f :: layout(~{::p})}\"></t:block><p t:text=\"s\">x</p>")
        .assertSuccess();
  }

  /** petite-vue interpolates script text: a value after an open {{ in a script runs as code. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>var t = '{{'; var v = [[s]];</script>",
        "<script>var t = '{{'; var v = [[s]] + '}}';</script>",
        "<script>var t = '{{}} {{'; var v = [[s]];</script>",
      })
  void expressionAfterOpenMustacheInScriptIsABuildError(String body) {
    compile(body).assertError("t.html:1:", "[[...]] is inside a client-side {{ ... }} interpolation", "data-*");
  }

  /**
   * A static {{ with no }} after it in the template's text: a value written after it, in this
   * template or in one that inserts it, would be inside the interpolation.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{</p>",
        "<p t:fragment=\"f\">{{ </p>",
        "<p>a</p>{{",
        "<code>{{</code>",
        "<p>{{ a }} {{</p>",
        "<p>{{ a }}</p><p>{{ <b>x</b></p>",
        "<p>&lbrace;&#123;</p>",
        "<p>{{ <!-- }} --></p>",
        "<p>{{ [[s]]</p>",
        "<p>{{ [['}}']]</p>",
        "<title>{{</title>",
        "<textarea>{{</textarea>",
        "<p>{{</p><script>var a = '}}';</script>",
      })
  void unclosedMustacheIsABuildError(String body) {
    compile(body).assertError("t.html:1:", "a client-side {{ is never closed", "{<!---->{");
  }

  @Test
  void unclosedMustacheErrorNamesItsPosition() {
    Compilation c = compile("\n<p>{{ a }}\n  x {{</p>");
    c.assertError("t.html:3:5:", "a client-side {{ is never closed");
    assertEquals(1, c.errors().size(), String.join("\n", c.errors()));
    // a character reference moves offsets: the position is the start of the text
    compile("\n<p>x &lbrace;{</p>").assertError("t.html:2:4:", "a client-side {{ is never closed");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{<!---->{</p>",
        "<p>{<b></b>{ x</p>",
        "<p>{ {</p>",
        "<p>{{ <b>x</b> }}</p>",
        "<p>{{ a }}</p>",
        "<p>&amp;#123;&amp;#123;</p>",
        "<p title=\"{{\">x</p>",
        "<!-- {{ --><p>x</p>",
        "<script>const open = '{{';</script>",
        "<p t:text=\"s\">{{</p>",
        "<p t:remove=\"all\">{{</p>",
        "<p t:remove=\"body\">{{</p>",
        "<p t:insert=\"~{::f}\">{{</p><t:block t:fragment=\"f\">x</t:block>",
      })
  void closedOrNonTextMustacheCompiles(String body) {
    compile(body).assertSuccess();
  }

  private static final String COND_HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s, boolean c, java.util.List<String> l\">";

  private static Compilation compileCond(String body) {
    return Compilation.builder().template("t.html", COND_HEADER + body + "</div>").compile();
  }

  /**
   * A }} that may not render does not close a static {{: with s = "'PW'+'NED'}}" the value would
   * close it and the frameworks would run it.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{<t:block t:remove=\"all\">}}</t:block>[[s]]</p>",
        "<p>{{<b t:remove=\"all\">}}</b>[[s]]</p>",
        "<p>{{<t:block t:if=\"c\">}}</t:block>[[s]]</p>",
        "<p>{{<t:block t:unless=\"c\">}}</t:block><t:block t:text=\"s\"></t:block></p>",
        "<p>{{<t:block t:each=\"x : ${l}\" t:if=\"${false}\">}}</t:block>[[s]]</p>",
        "<p>{{<t:block t:text=\"'x'\">}}</t:block>[[s]]</p>",
        "<p>{{<b t:remove=\"body\">}}</b>[[s]]</p>",
        "<p>{{<b t:remove=\"all-but-first\">}}</b>[[s]]</p>",
        "<p>{{<b t:switch=\"s\"><i t:case=\"'a'\">}}</i></b>[[s]]</p>",
        "<p>{{<t:block t:replace=\"~{::f}\">}}</t:block>[[s]]</p><t:block t:fragment=\"f\">x</t:block>",
        "<p>{{<b t:if=\"c\"><i>}}</i></b>[[s]]</p>",
        "<p><b t:if=\"c\">{{</b> }}</p>",
      })
  void mustacheClosedOnlyByContentThatMayNotRenderIsABuildError(String body) {
    compileCond(body).assertError("t.html:1:", "a client-side {{ is never closed", "{<!---->{");
  }

  @Test
  void fragmentClosedOnlyByContentThatMayNotRenderIsABuildError() {
    for (String inner : new String[] {"t:if=\"false\"", "t:remove=\"all\""}) {
      Compilation.builder()
          .template(
              "frag.html",
              "<div xmlns:t=\"https://tessera.dev\"><t:block t:fragment=\"f\">{{<t:block " + inner + ">}}</t:block></t:block></div>")
          .template("t.html", COND_HEADER + "<p><t:block t:insert=\"~{frag :: f}\"></t:block>[[s]]</p></div>")
          .compile()
          .assertError("frag.html:1:", "a client-side {{ is never closed");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ <b t:if=\"c\">x</b> }}</p>",
        "<p t:if=\"c\">{{ a }}</p>",
        "<p t:each=\"x : ${l}\">{{ a }} [[x]]</p>",
        "<p><b t:if=\"c\">{{ a <i>x</i> }}</b> [[s]]</p>",
        "<p>{{ <b t:remove=\"tag\">}}</b> [[s]]</p>",
        "<p>{{ <b t:remove=\"none\" t:title=\"s\">}}</b> [[s]]</p>",
      })
  void mustacheClosedByContentThatAlwaysRendersCompiles(String body) {
    compileCond(body).assertSuccess();
  }

  private static final String FRAG_HEADER = "<div xmlns:t=\"https://tessera.dev\">";
  private static final String FRAG = "<t:block t:fragment=\"f\">{{</t:block>}}";
  private static final String INSERT = "<p><t:block t:insert=\"~{frag :: f}\"></t:block>[[s]]</p>";

  /**
   * A fragment renders alone where another template inserts it, so a }} after it in its own file
   * does not come along: an element that opens a {{ must close it itself. With s = "'PW'+'NED'}}"
   * each of these would render a {{ that Vue or AngularJS runs.
   */
  @Test
  void mustacheClosedOnlyOutsideItsElementIsABuildError() {
    String[][] cases = {
      {"frag.html", FRAG, INSERT},
      {"frag.html", "<div><b t:fragment=\"f\" t:remove=\"tag\">{{</b>}}</div>", INSERT},
      {"frag.html", "<p>x<t:block t:fragment=\"f\">{{</t:block>}}</p>", INSERT},
      {"frag.html", FRAG, "<p><t:block t:replace=\"~{frag :: f}\"></t:block>[[s]]</p>"},
      {"t.html", "x", "<t:block t:fragment=\"g\">{{</t:block>}}<p><t:block t:insert=\"~{::g}\"></t:block>[[s]]</p>"},
      {"frag.html", "<t:block t:fragment=\"f\"><t:block t:fragment=\"inner\">{{</t:block>}}</t:block>",
          "<p><t:block t:insert=\"~{frag :: inner}\"></t:block>[[s]]</p>"},
      {"frag.html", "<t:block t:fragment=\"f(String x)\"><t:block t:fragment=\"inner\">{{</t:block>[[x]]</t:block>}}",
          "<p><t:block t:insert=\"~{frag :: f(x=${s})}\"></t:block></p>"},
      {"frag.html", FRAG,
          "<p><t:block t:each=\"x : ${l}\"><t:block t:insert=\"~{frag :: f}\"></t:block>[[x]]</t:block></p>"},
      // An element in a body that does not render can still be inserted.
      {"frag.html", "<div t:remove=\"all\"><b t:fragment=\"f\">{{</b>}}</div>", INSERT},
      {"frag.html", "<p t:text=\"'x'\"><b t:fragment=\"f\">{{</b>}}</p>", INSERT},
      // An element is read from an empty state, also inside a {{ its parent opens.
      {"t.html", "x", "<p>{{ <b>a {{</b> }}</p>"},
      {"t.html", "x", "<p>{{ <b t:if=\"c\">x {{ y</b> }}</p>"},
      {"frag.html", "<p>{{ <b t:fragment=\"f\">x {{ y</b> }}</p>", INSERT},
      {"frag.html", "<div t:remove=\"all\">{{ <b t:fragment=\"f\">x {{ y</b></div>", INSERT},
      // b closes the {{ of p and opens one: only that one is reported.
      {"t.html", "x", "<p>{{ <b>}}{{</b></p>"},
    };
    for (String[] c : cases) {
      Compilation result =
          Compilation.builder()
              .template("frag.html", FRAG_HEADER + c[1] + "</div>")
              .template("t.html", COND_HEADER + c[2] + "</div>")
              .compile();
      result.assertError(c[0] + ":1:", "a client-side {{ is never closed inside <", "{<!---->{");
      assertEquals(1, result.errors().size(), c[1] + " " + c[2] + "\n" + String.join("\n", result.errors()));
    }
  }

  @Test
  void unclosedMustacheErrorNamesItsElement() {
    compileCond("\n<p>\n  <b>x {{</b> }}</p>")
        .assertError("t.html:3:8:", "a client-side {{ is never closed inside <b>", "inside the same element");
  }

  @Test
  void fragmentThatClosesItsOwnMustacheCompiles() {
    Compilation.builder()
        .template(
            "frag.html",
            FRAG_HEADER + "<t:block t:fragment=\"f\">{{ a }}</t:block><p t:fragment=\"g\">{{ <b>x</b> }}</p>"
                + "<b t:fragment=\"h\" t:remove=\"tag\">{{ a }} {{ b }}</b></div>")
        .template(
            "t.html",
            COND_HEADER + INSERT + "<div><t:block t:insert=\"~{frag :: g}\"></t:block>[[s]]</div>"
                + "<p><t:block t:replace=\"~{frag :: h}\"></t:block>[[s]]</p></div>")
        .compile()
        .assertSuccess();
  }
}
