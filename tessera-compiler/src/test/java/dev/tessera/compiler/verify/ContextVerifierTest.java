package dev.tessera.compiler.verify;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The context verifier end to end (#367): it runs in every build, after the profile, and its
 * errors name the hole's file, line and column. The rule table itself is in
 * {@link ContextVerifierRulesTest}.
 */
class ContextVerifierTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\">" + body + "</div>")
        .compile();
  }

  @Test
  void anOrdinaryPageCompiles() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Item", "package com.example; public record Item(String name, int n) {}")
            .template(
                "page.html",
                "<!DOCTYPE html>\n<html " + NS + " t:import=\"java.util.List, com.example.Item\" t:params=\"List<Item> items, String q\">\n"
                    + "<head><title t:text=\"q\">t</title><meta name=\"description\" t:content=\"q\"></head>\n<body>\n"
                    + "<h1 class=\"x\" t:classappend=\"q\" t:title=\"q\" t:id=\"q\" t:lang=\"q\" t:dir=\"q\">[[${q}]]</h1>\n"
                    + "<table><thead><tr><th>n</th></tr></thead><tbody><tr t:each=\"i : items\"><td t:text=\"i.name\">x</td>"
                    + "<td><a t:href=\"@{/items/{n}(n=${i.n})}\" t:text=\"i.n\">1</a></td></tr></tbody></table>\n"
                    + "<ul><li t:each=\"i : items\" t:if=\"i.n > 0\">[[${i.name}]]</li></ul>\n"
                    + "<select><option t:each=\"i : items\" t:value=\"i.name\" t:text=\"i.name\">o</option></select>\n"
                    + "<textarea t:text=\"q\"></textarea><script>var items = [[${items}]]; var size = [[${items.size()}]]; if (1<size) {}</script>\n"
                    + "</body>\n</html>")
            .compile()
            .assertSuccess();
    String html = c.render("page", model("items", List.of(c.newInstance("com.example.Item", "a<b", 2)), "q", "q\""));
    assertTrue(html.contains("<td>a&lt;b</td>"), html);
  }

  @Test
  void aValueABrowserRunsIsAnErrorAtItsPosition() {
    // The profile lets JSON into an SVG <script>; a browser runs it as script that isn't an HTML
    // script's data, so the escaper doesn't apply (security-architecture.md §11.2).
    compile("\n<svg><script>var a = [[${s}]];</script></svg>")
        .assertError("t.html:2:26: error: a browser reads this value (written as JSON for a script) inside SVG <script>",
            "[verifier:JSON_NOT_IN_SCRIPT]");
  }

  @Test
  void theVerifierAloneRejectsWhatTheProfileRejectsFirst() {
    String table = "<table><tr><td>a</td></tr>[[${s}]]</table>";
    compile(table).assertError("as table text and moves it in front of the table", "[verifier:TEXT_IN_TABLE]");
    Compilation v = Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\">" + table + "</div>")
        .verifierOnly()
        .compile();
    v.assertError("t.html:1:", "as table text and moves it in front of the table", "[verifier:TEXT_IN_TABLE]");
    assertTrue(v.errors().stream().allMatch(e -> e.contains("[verifier:")), v.errors().toString());
  }

  @Test
  void layoutsAreCheckedWithTheirArgumentsAtEachCallSite() {
    String layout =
        "<!DOCTYPE html>\n<html " + NS + " t:import=\"dev.tessera.runtime.Fragment\""
            + " t:fragment=\"layout(Fragment content, String title, Fragment head)\">\n"
            + "<head><title t:text=\"title\">t</title><t:block t:replace=\"head\"></t:block></head>\n"
            + "<body><main t:insert=\"content\">c</main></body></html>";
    Compilation c =
        Compilation.builder()
            .template("layout.html", layout)
            // null: nothing in the head.
            .template("a.html", "<html " + NS + " t:params=\"String s\" t:replace=\"~{layout :: layout(~{::body}, s, null)}\">"
                + "<body t:remove=\"tag\"><p t:text=\"s\">p</p></body></html>")
            // Head content from the page: checked in the head.
            .template("b.html", "<html " + NS + " t:params=\"String s\" t:replace=\"~{layout :: layout(~{::main}, s, ~{::link})}\">"
                + "<head><link rel=\"stylesheet\" t:href=\"@{/b.css}\"></head><main t:text=\"s\">m</main></html>")
            .compile()
            .assertSuccess();
    assertTrue(c.render("b", model("s", "x")).contains("<head><title>x</title><link rel=\"stylesheet\" href=\"/b.css\"></head>"));
    // Text where only head content may go is moved into the body by a browser: rejected at the call.
    Compilation bad =
        Compilation.builder()
            .template("layout.html", layout)
            .template("c.html", "<html " + NS + " t:params=\"String s\" t:replace=\"~{layout :: layout(~{::main}, s, ~{h :: h(s)})}\">"
                + "<main>m</main></html>")
            .template("h.html", "<div " + NS + "><t:block t:fragment=\"h(String v)\">[[${v}]]</t:block></div>")
            .compile();
    assertFalse(bad.success());
    bad.assertError("c.html:1:", "as text in the <head>", "in fragment h :: h, inserted here, at h.html:1:", "[verifier:TEXT_OUTSIDE_BODY]");
  }

  @Test
  void richTextRebuiltByARecursiveFragmentIsRejected() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Node", "package com.example; import java.util.List;"
                + " public record Node(String tag, String text, List<Node> children) {}")
            .template(
                "rich.html",
                "<div " + NS + " t:import=\"java.util.List, com.example.Node\" t:params=\"List<Node> nodes\">"
                    + "<t:block t:fragment=\"nodes(List<Node> ns)\"><t:block t:each=\"n : ns\">"
                    + "<t:block t:if=\"n.tag == null\">[[${n.text}]]</t:block>"
                    + "<p t:if=\"n.tag == 'p'\" t:insert=\"~{rich :: nodes(n.children)}\"></p>"
                    + "<b t:if=\"n.tag == 'b'\" t:insert=\"~{rich :: nodes(n.children)}\"></b>"
                    + "<ul t:if=\"n.tag == 'ul'\" t:insert=\"~{rich :: nodes(n.children)}\"></ul>"
                    + "<li t:if=\"n.tag == 'li'\" t:insert=\"~{rich :: nodes(n.children)}\"></li>"
                    + "</t:block></t:block>"
                    + "<section t:insert=\"~{rich :: nodes(nodes)}\"></section></div>")
            .compile();
    c.assertError("rich.html:1:", "markup structure depends on data", "html.sanitize", "guide section 4.10, Rich text");
    // One error for the recursion; the other is the parse error a <ul> in a <p> makes (#406).
    assertEquals(1, c.errors().stream().filter(e -> !e.endsWith("[verifier:PARSE_ERROR]")).count(), c.errors().toString());
  }

  @Test
  void partialsAreCheckedInTheContextTheirRootImplies() {
    // A <tr> partial (an htmx swap into a table) is checked as tbody content too.
    Compilation.builder()
        .template("row.html", "<tr " + NS + " t:params=\"String s\"><td t:text=\"s\">x</td></tr>")
        .compile()
        .assertSuccess();
    Compilation.builder()
        .template("row.html", "<tr " + NS + " t:params=\"String s\">[[${s}]]<td>x</td></tr>")
        .verifierOnly()
        .compile()
        .assertError("row.html:1:", "[verifier:TEXT_IN_TABLE]");
  }

  @Test
  void aTableEndingInsideSvgInACellIsAnErrorInEveryBuild() {
    // #398: the profile accepts this; the verifier's parsers (and Firefox) leave the table after the
    // inner </table>, Chrome and Safari don't, so the computed type decides where the input goes.
    // Exclusion (a) rejects the <svg> in the cell (#406).
    String inner = "<svg><foreignObject><div><table></table></div></foreignObject></svg>";
    Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\"><table><tbody><tr><td>" + inner
            + "</td><input t:type=\"s\"></tr></tbody></table></div>")
        .verifierOnly()
        .compile()
        .assertError("t.html:1:", "<svg> inside <td>", "[verifier:FOREIGN_IN_TABLE]");
    // A static fragment that brings the </table>.
    Compilation.builder()
        .template("t.html", "<div " + NS + " t:params=\"String s\"><table><tr><td><svg><foreignObject><table>"
            + "<t:block t:replace=\"~{parts :: end}\"></t:block></foreignObject></svg></td><td t:text=\"s\">x</td>"
            + "</tr></table></div>")
        .template("parts.html", "<div " + NS + "><t:block t:fragment=\"end\"></table></t:block></div>")
        .verifierOnly()
        .compile()
        .assertError("t.html:1:", "[verifier:FOREIGN_IN_TABLE]");
    // The same SVG outside the table compiles.
    compile("<svg><foreignObject><div>[[${s}]]</div></foreignObject></svg>"
        + "<table><tbody><tr><td t:title=\"s\">x</td></tr></tbody></table>").assertSuccess();
  }
}
