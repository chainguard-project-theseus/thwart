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
    assertEquals("<svg><title v-pre><!---->{<!---->{x}}</title><title v-pre>a <!---->{<!---->{x}}</title></svg>",
        render("<svg><title t:text=\"s\">t</title><title>a [[s]]</title></svg>", "{{x}}"));
    assertEquals("<math><textarea v-pre><!---->{<!---->{x}}</textarea></math>",
        render("<math><textarea>[[s]]</textarea></math>", "{{x}}"));
  }

  @Test
  void titleAndTextareaKeepBracesUnderVPre() {
    // RCDATA: a comment would show as text there, so the element gets v-pre instead (#549).
    assertEquals("<title v-pre>{{x}} &amp;</title>", render("<title t:text=\"s\">t</title>", "{{x}} &"));
    assertEquals("<title v-pre>a {{x}} &amp;</title>", render("<title>a [[s]]</title>", "{{x}} &"));
    assertEquals("<textarea v-pre>\n{{x}}</textarea>", render("<textarea t:text=\"s\"></textarea>", "{{x}}"));
    assertEquals("<textarea v-pre>\n{{x}}</textarea>", render("<textarea>[[s]]</textarea>", "{{x}}"));
    assertEquals("<TITLE v-pre>{{x}}</TITLE>", render("<TITLE t:text=\"s\">t</TITLE>", "{{x}}"));
    // An <annotation-xml> whose t:attr names only other attributes keeps its HTML encoding: an HTML title.
    assertEquals("<math><annotation-xml encoding=\"text/html\" id=\"{{x}}\"><title v-pre>{{x}}</title></annotation-xml></math>",
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

  /** A value inside a static {{ }}: the {{ is not closed in its own text. */
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
    compile(body).assertError("t.html:1:", NOT_CLOSED, "data-*");
  }

  private static final String NOT_CLOSED = "a client-side {{ is not closed by }} in the same text";

  @Test
  void mustacheErrorNamesTheOpeningPosition() {
    compile("\n<p>\n  {{ greet('[[s]]') }}</p>").assertError("t.html:3:3:");
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

  private static final String TEXT_IN_TABLE = "[verifier:TEXT_IN_TABLE]";
  private static final String PARSE_ERROR = "[verifier:PARSE_ERROR]";
  private static final String RAW_NOT_FLOW = "[verifier:RAW_NOT_FLOW]";

  /** Compiles {@code "\n" + body} and asserts it fails with an error on line 2 that names {@code check}. */
  private static void assertRejected(String bodyAndCheck) {
    int bar = bodyAndCheck.lastIndexOf('|');
    String body = bodyAndCheck.substring(0, bar);
    Compilation c = compile("\n" + body);
    c.assertError("t.html:2:", bodyAndCheck.substring(bar + 1));
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  /**
   * #348, now the build-time verifier's: a browser moves text out of {@code table}, {@code tbody},
   * {@code thead}, {@code tfoot}, {@code tr} and {@code colgroup} in front of the table but keeps
   * comments inside it, so the braces of a broken {@code {{} join up again. The verifier reports
   * any value a browser reads as table text, and raw HTML outside a cell.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<table>[[s]]<tr><td>x</td></tr></table>|" + TEXT_IN_TABLE,
        "<table><tr><td>x</td></tr><tr t:text=\"s\"></tr></table>|" + TEXT_IN_TABLE,
        "<table><tbody>[[s]]<tr><td>x</td></tr></tbody></table>|" + TEXT_IN_TABLE,
        "<table><thead>x [[s]]</thead></table>|" + TEXT_IN_TABLE,
        "<table><tfoot t:text=\"s\"></tfoot></table>|" + TEXT_IN_TABLE,
        "<table><tr>[[s]]<td>x</td></tr></table>|" + TEXT_IN_TABLE,
        "<TABLE><TR>[[s]]</TR></TABLE>|" + TEXT_IN_TABLE,
        "<table><colgroup>[[s]]</colgroup></table>|" + TEXT_IN_TABLE,
        "<table><t:block>[[s]]</t:block></table>|" + TEXT_IN_TABLE,
        "<table><t:block t:text=\"s\"></t:block></table>|" + TEXT_IN_TABLE,
        "<table><tr><span t:remove=\"tag\">[[s]]</span></tr></table>|" + TEXT_IN_TABLE,
        "<table><t:block t:if=\"${s != null}\"><t:block>[[s]]</t:block></t:block></table>|" + TEXT_IN_TABLE,
        "<table><tr t:utext=\"h\"></tr></table>|" + RAW_NOT_FLOW,
        "<table><tr>[(h)]</tr></table>|" + RAW_NOT_FLOW,
        // <table> is HTML in a browser and ends the <svg>: the profile rejects it first.
        "<svg><table>[[s]]</table></svg>|malformed markup: <table> inside <svg> or <math> is HTML",
      })
  void computedTextDirectlyInTableIsABuildError(String bodyAndCheck) {
    assertRejected(bodyAndCheck);
  }

  @Test
  void tableTextErrorNamesThePosition() {
    compile("\n<table>\n  <tr>[[s]]</tr></table>")
        .assertError(
            "t.html:3:9:",
            "a browser reads this value as table text and moves it in front of the table; put it in a <td> or <th>;"
                + " a browser opened the <tr> it is in at t.html:3:3",
            TEXT_IN_TABLE);
    compile("\n<table><tbody>\n <t:block t:text=\"s\"/></tbody></table>").assertError("t.html:3:19:", TEXT_IN_TABLE);
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
   * #348, now the build-time verifier's: a table part where a browser does not keep it makes the
   * browser close elements up to one that takes it (a {@code <tr>} ends a {@code <td>}), so the
   * text after it is read in the table and moved in front of it. A {@code <form>} in a table is
   * closed at once. Each is a parse error, and the value after it table text.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<table><tr><td><tr></tr>[[s]]</td></tr></table>|" + PARSE_ERROR,
        "<table><tr><td><p><tr></tr>[[s]]</p></td></tr></table>|" + PARSE_ERROR,
        // The profile's closed <select> content model rejects it first.
        "<table><tr><td><select><tr></tr>[[s]]</select></td></tr></table>|<tr> inside <select> is not supported",
        "<table><tr><td><tbody></tbody>[[s]]</td></tr></table>|" + PARSE_ERROR,
        "<table><tr><td><th></th>[[s]]</td></tr></table>|" + PARSE_ERROR,
        "<table><tr><td><col>[[s]]</td></tr></table>|" + PARSE_ERROR,
        "<table><caption><tr></tr>[[s]]</caption></table>|" + PARSE_ERROR,
        "<table><caption><b><td></td>[[s]]</b></caption></table>|" + PARSE_ERROR,
        "<table><div><tbody></tbody>[[s]]</div></table>|" + PARSE_ERROR,
        "<table><b><tr></tr>[[s]]</b></table>|" + PARSE_ERROR,
        "<table><form>[[s]]</form></table>|" + PARSE_ERROR,
        "<table><tr><form>[[s]]</form></tr></table>|" + PARSE_ERROR,
      })
  void misplacedTablePartIsABuildError(String bodyAndCheck) {
    assertRejected(bodyAndCheck);
    if (bodyAndCheck.endsWith(PARSE_ERROR)) {
      compile("\n" + bodyAndCheck.substring(0, bodyAndCheck.lastIndexOf('|'))).assertError("t.html:2:", TEXT_IN_TABLE);
    }
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
        "<table><tbody t:insert=\"~{f :: text(s)}\"></tbody></table>|" + TEXT_IN_TABLE,
        "<table><tr t:replace=\"~{f :: text(s)}\"></tr></table>|" + TEXT_IN_TABLE,
        "<table><tr t:replace=\"~{f :: tagless(s)}\"></tr></table>|" + TEXT_IN_TABLE,
        "<table><t:block t:insert=\"~{f :: nested(s)}\"></t:block></table>|" + TEXT_IN_TABLE,
        "<table><tr><td t:insert=\"~{f :: row(s)}\"></td></tr></table>|" + PARSE_ERROR,
        "<table><tr><td><b t:replace=\"~{f :: row(s)}\"></b>[[s]]</td></tr></table>|" + PARSE_ERROR,
        "<table><tr><td><b t:replace=\"~{f :: row(s)}\"></b>[[s]]</td></tr></table>|" + TEXT_IN_TABLE,
        "<table><tbody t:insert=\"~{f :: form(s)}\"></tbody></table>|" + PARSE_ERROR,
        "<table><tbody t:insert=\"~{f :: form(s)}\"></tbody></table>|" + TEXT_IN_TABLE,
        // A Fragment value that starts with a table part: rejected where it is built (TODO(#561)).
        "<t:block t:replace=\"~{f :: layout(~{::tr})}\"></t:block><table><tr><td t:text=\"s\">x</td></tr></table>|"
            + TABLE_PART_VALUE,
        // Rows in a <div>: a browser drops the <tr> and <td> tags, a parse error (#406).
        "<div t:insert=\"~{f :: rows(s)}\"></div>|" + PARSE_ERROR,
      })
  void insertedTextOrTablePartInTableIsABuildError(String bodyAndCheck) {
    int bar = bodyAndCheck.lastIndexOf('|');
    String body = bodyAndCheck.substring(0, bar);
    Compilation c = compileWithFragments("\n" + body);
    c.assertError("t.html:2:", bodyAndCheck.substring(bar + 1));
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
        .assertError("f.html:2:27:", "a browser parses this Fragment value in a table outside any cell", RAW_NOT_FLOW);
  }

  private static final String TABLE_PART_VALUE = "} writes <tr>, which is not supported in a Fragment value";

  /**
   * #348: a {@code ~{::tr}} passed as a {@code Fragment} and inserted in a {@code <td>} ends the
   * cell, so the value after it is moved in front of the table. The verifier composes the
   * argument only where the callee inserts the parameter by itself (TODO(#561)), so a value that
   * starts with a table part is rejected where it is built, also through a ternary or t:with.
   */
  @ParameterizedTest
  @ValueSource(strings = {"${body}", "${flag ? body : null}", "${body ?: null}", "${b}"})
  void tablePartFragmentValueIsABuildError(String insert) {
    String with = insert.equals("${b}") ? " t:with=\"b=${body}\"" : "";
    Compilation c =
        Compilation.builder()
            .template(
                "f.html",
                "<div xmlns:t=\"https://tessera.dev\" t:fragment=\"slot(Fragment body, boolean flag, String s)\">"
                    + "<table><tr><td" + with + "><t:block t:insert=\"" + insert + "\"/>[[${s}]]</td></tr></table></div>")
            .template(
                "t.html",
                HEADER + "\n<t:block t:replace=\"~{f :: slot(~{::tr}, true, ${s})}\"></t:block>"
                    + "<table><tr><td>x</td></tr></table></div>")
            .compile();
    c.assertError("t.html:2:", "~{:: tr" + TABLE_PART_VALUE);
    assertNull(c.generatedSource("tessera.generated.TTemplate"), insert);
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
    compile(body).assertError("t.html:1:", NOT_CLOSED, "data-*");
  }

  /**
   * #336: a {{ is closed in the literal that opens it, so nothing another node writes (a value, a
   * tag, a fragment, text that may not render) is ever inside one. Coarse: also a {{ }} around a tag.
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
        "<p>{{ <b>x</b> }}</p>",
        "<p>{{<t:block t:if=\"${s != null}\">}}</t:block>[[s]]</p>",
        "<p>{{<b t:remove=\"all\">}}</b>[[s]]</p>",
        "<p><b>x {{</b> }}</p>",
        "<p t:text=\"s\"><b t:fragment=\"f\">{{</b>}}</p>",
        "<p t:remove=\"all\">{{</p>",
      })
  void mustacheNotClosedInItsOwnTextIsABuildError(String body) {
    compile(body).assertError("t.html:1:", NOT_CLOSED, "{<!---->{");
  }

  @Test
  void unclosedMustacheErrorNamesItsPosition() {
    Compilation c = compile("\n<p>{{ a }}\n  x {{</p>");
    c.assertError("t.html:3:5:", NOT_CLOSED);
    assertEquals(1, c.errors().size(), String.join("\n", c.errors()));
    // a character reference moves offsets: the position is the start of the text
    compile("\n<p>x &lbrace;{</p>").assertError("t.html:2:4:", NOT_CLOSED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{<!---->{</p>",
        "<p>{<b></b>{ x</p>",
        "<p>{ {</p>",
        "<p>{{ a }}</p>",
        "<p>{</p>",
        "<p>{[[s]]}</p>",
        "<p>&amp;#123;&amp;#123;</p>",
        "<p title=\"{{\">x</p>",
        "<!-- {{ --><p>x</p>",
        "<script>const open = '{{}}';</script>",
        "<p t:text=\"s\">{{</p>",
        "<p t:insert=\"~{::f}\">{{</p><t:block t:fragment=\"f\">x</t:block>",
        "<p t:if=\"${s != null}\">{{ a }}</p>",
        // Elements whose attributes all keep the tag are boundaries.
        "<p>a {<b t:text=\"s\">x</b>{ b</p>",
        "<p>a {<b t:title=\"s\" t:classappend=\"'c'\">x</b>{ b</p>",
        "<div>a {<div t:with=\"v=${s}\" t:utext=\"h\">x</div>{ b</div>",
      })
  void closedOrNonTextMustacheCompiles(String body) {
    compile(body).assertSuccess();
  }

  /**
   * #336: a '{' at the edge of a text node meets a '{' from the text next to it where no tag or
   * comment always renders between them: a t:block, a t:if, a fragment's edge.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{<t:block>{ [[s]] }}</t:block></p>",
        "<p>a {<span t:if=\"${s != null}\">x</span>{ b }}</p>",
        "<p><t:block t:if=\"${s != null}\">{</t:block>x</p>",
        "<t:block t:fragment=\"f\">{</t:block>",
        "<p><b t:remove=\"tag\">x {</b></p>",
        // all-but-first drops the elements after the first and keeps the text around them.
        "<div t:remove=\"all-but-first\"><b>x</b>{<i>y</i>{ [[s]] }}</div>",
        "<div t:remove=\"all-but-first\"><b>x</b>a {<i>y</i></div>",
        // A fragment with parameters is dropped where it is defined.
        "<p>{<b t:fragment=\"f(String x)\">[[x]]</b>{ [[s]] }}</p>",
        // Fail closed: only attributes known to keep the tag make a boundary.
        "<p>a {<span t:remove=\"none\">x</span></p>",
        "<p>a {<span t:case=\"'x'\">x</span></p>",
        // An empty value at the end of the text: the '{' before it is the text's last.
        "<p><t:block>a {[[s]]</t:block>x</p>",
      })
  void braceAtAnOpenTextEdgeIsABuildError(String body) {
    compile(body).assertError("t.html:1:", "with '{' next to a <t:block>", "<!---->");
  }

  /** A browser joins a CDATA section in foreign content to the text around it. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><text><![CDATA[{]]><![CDATA[{]]> [[s]] }}</text></svg>",
        "<svg><text><![CDATA[{]]>[[a]]{ [[s]] }}</text></svg>",
        "<math><mi><![CDATA[ x { y ]]></mi></math>",
      })
  void braceInCdataIsABuildError(String body) {
    Compilation c = compile(body);
    c.assertError("t.html:1:", "'{' in a CDATA section is not supported");
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  @Test
  void cdataWithoutBracesCompiles() {
    compile("<svg><text><![CDATA[ x < y ]]>[[s]]</text></svg>").assertSuccess();
  }

  /** An empty value writes nothing, so a '{' on each side of it forms {{ (s = null, t = the payload). */
  @ParameterizedTest
  @ValueSource(strings = {"<p>{[[s]]{ [[t]] }}</p>", "<div>{[(h)]{ [[t]] }}</div>", "<p>{[[s]][[a]]{ [[t]] }}</p>"})
  void braceOnBothSidesOfAValueIsABuildError(String body) {
    Compilation c =
        Compilation.builder()
            .template("t.html", HEADER.replace("String b,", "String b, String t,") + body + "</div>")
            .compile();
    c.assertError("t.html:1:", "this text starts with '{' right after a value that follows a '{'", "<!---->{");
    assertNull(c.generatedSource("tessera.generated.TTemplate"), body);
  }

  /** t:errors renders its element only when there are errors, so the texts around it can meet. */
  @Test
  void braceNextToErrorsIsABuildError() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Owner", "package com.example; public record Owner(String firstName) {}")
            .template(
                "t.html",
                "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.Owner\" t:params=\"Owner owner\""
                    + " t:object=\"owner\">\n<p>{<span t:errors=\"firstName\">E</span>{ [[${owner.firstName}]] }}</p></form>")
            .compile();
    c.assertError("t.html:2:4:", "this text ends with '{' next to a <t:block>, t:if, t:each, t:remove, t:replace, t:errors");
    c.assertError("t.html:2:", "this text starts with '{'");
    assertNull(c.generatedSource("tessera.generated.TTemplate"));
  }

  @Test
  void braceAcrossAFragmentBoundaryIsABuildError() {
    // #336 re-review case 1: one '{' in the caller's text, the other from the inserted fragment.
    Compilation.builder()
        .template("frag.html", "<div xmlns:t=\"https://tessera.dev\"><t:block t:fragment=\"f\">{ [[${1}]] }}</t:block></div>")
        .template("t.html", HEADER + "<p>x<t:block t:insert=\"~{frag :: f}\"></t:block></p></div>")
        .compile()
        .assertError("frag.html:1:", "with '{' next to a <t:block>");
  }
}
