package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Client-side template injection is outside the guarantee (#679, reverses #248): client frameworks
 * that compile the server's DOM as a template are unsupported, and a strict CSP is the backstop.
 * A value is escaped as plain text, the same in element text, RCDATA and attributes: {@code {{}
 * renders as {@code {{}, no comment breaks it and no {@code v-pre} is added. Static braces are
 * ordinary text.
 */
class ClientTemplateInjectionTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\""
          + " t:params=\"String s, String a, String b, SafeHtml h\">";
  private static final String PAYLOAD = "{{ constructor.constructor('alert(1)')() }}";
  private static final String ESCAPED = "{{ constructor.constructor(&#39;alert(1)&#39;)() }}";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, String s) {
    String out =
        compile(body).assertSuccess().render("t", Map.of("s", s, "a", "", "b", "", "h", dev.tessera.runtime.SafeHtml.EMPTY));
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void bracesInAValueArePlainEscapedText() {
    assertEquals("<p>a " + ESCAPED + " b</p>", render("<p>a [[s]] b</p>", PAYLOAD));
    assertEquals("<span>" + ESCAPED + "</span>", render("<span t:text=\"s\">x</span>", PAYLOAD));
    assertEquals("<p>{{ x }}}}</p>", render("<p>{{ x }}[[s]]</p>", "}}"));
  }

  @Test
  void titleAndTextareaGetNoVPre() {
    assertEquals("<title>{{x}} &amp;</title>", render("<title t:text=\"s\">t</title>", "{{x}} &"));
    assertEquals("<title>a {{x}}</title>", render("<title>a [[s]]</title>", "{{x}}"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea t:text=\"s\"></textarea>", "{{x}}"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea>[[s]]</textarea>", "{{x}}"));
  }

  @Test
  void attributesAndScriptsAreNotChanged() {
    assertEquals("<i title=\"{{x}}\" id=\"{{x}}\">i</i>", render("<i t:title=\"s\" t:id=\"s\">i</i>", "{{x}}"));
    assertEquals("<a href=\"{{x}}\">a</a>", render("<a t:href=\"s\">a</a>", "{{x}}"));
    assertEquals("<script>var v = (\"\\u007b\\u007bx}}\");</script>", render("<script>var v = [[s]];</script>", "{{x}}"));
  }

  /** Static braces anywhere are the author's text, formerly rejected by the static-brace rule (#571). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ greet('[[s]]') }}</p>",
        "<p>{{</p>",
        "<p>}} [[s]]</p>",
        "<p>{[[s]]{ [[a]] }}</p>",
        "<title>{{ [[s]] }}</title>",
        "<textarea>{{</textarea>",
        "<p v-pre>{{ [[s]] }}</p>",
        "<script>var v = [[s]]; var t = '{{';</script>",
      })
  void staticBracesCompile(String body) {
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
   * {@code thead}, {@code tfoot}, {@code tr} and {@code colgroup} in front of the table. The
   * verifier reports any value a browser reads as table text, and raw HTML outside a cell.
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
  void textInTableCellsIsEscaped() {
    assertEquals(
        "<table><caption>" + ESCAPED + "</caption><tr><td>" + ESCAPED + "</td><th>" + ESCAPED + "</th></tr></table>",
        render("<table><caption>[[s]]</caption><tr><td>[[s]]</td><th t:text=\"s\">x</th></tr></table>", PAYLOAD));
    assertEquals(
        "<table><tr><td><table><tr><td>" + ESCAPED + "</td></tr></table><p>" + ESCAPED + "</p>" + ESCAPED
            + "</td></tr></table>",
        render(
            "<table><tr><td><table><tr><td>[[s]]</td></tr></table><p>[[s]]</p><t:block>[[s]]</t:block></td></tr></table>",
            PAYLOAD));
    assertEquals(
        "<table>\n  <tr>\n    <td>x</td>\n  </tr>\n</table>" + ESCAPED,
        render("<table>\n  <tr>\n    <td>x</td>\n  </tr>\n</table>[[s]]", PAYLOAD));
    // Without its tag the table is not there, so the text is ordinary element text.
    assertEquals(ESCAPED, render("<table t:remove=\"tag\">[[s]]</table>", PAYLOAD));
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
        // A Fragment value that starts with a table part, judged where it lands (#561).
        "<t:block t:replace=\"~{f :: layout(~{::tr})}\"></t:block><table><tr><td t:text=\"s\">x</td></tr></table>|"
            + PARSE_ERROR,
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

  /**
   * #348: a {@code ~{::tr}} passed as a {@code Fragment} and inserted in a {@code <td>} ends the
   * cell, so the value after it is moved in front of the table. The verifier parses it where it
   * lands, also through a ternary, an Elvis or a t:with alias (#561).
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
    c.assertError(PARSE_ERROR);
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
}
