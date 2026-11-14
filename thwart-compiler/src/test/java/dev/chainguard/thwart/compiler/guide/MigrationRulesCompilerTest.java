package dev.chainguard.thwart.compiler.guide;

import dev.chainguard.thwart.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * The compiler's errors, with file:line:col and the fix, for the forms {@code thwart-migrate} no
 * longer reports (#593, #595): it converts them like any other value, so these errors are the only
 * place an agent learns the fix. Each test is the negative case of a migrate rule that went; the
 * positive case (migrate converts without a report) is {@code MigratorTest} and {@code LayoutsTest}.
 */
class MigrationRulesCompilerTest {

  private static final String HEADER = "<div xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static final String LAYOUT = "<!DOCTYPE html>\n"
      + "<html xmlns:t=\"https://thwart.chainguard.dev\" lang=\"en\" t:fragment=\"layout(Fragment head, Fragment content)\">\n"
      + "<head>\n<t:block t:replace=\"head\"></t:block>\n<title>T</title>\n</head>\n"
      + "<body>\n<t:block t:replace=\"content\"></t:block>\n</body>\n</html>\n";

  private static Compilation compileWithLayout(String page) {
    return Compilation.builder().template("fragments/layout.html", LAYOUT).template("page.html", page).compile();
  }

  @Test
  void eventHandlerAndStyleNameTheFix() {
    compile("<b t:onclick=\"'go(' + s + ')'\">x</b>")
        .assertError("t.html:1:", "t:onclick is not allowed", "data-*", "<script type=\"application/json\">");
    compile("<b t:style=\"'color:' + s\">x</b>")
        .assertError("t.html:1:", "t:style is not allowed", "css.color(c)", "t:classappend");
  }

  @Test
  void svgAttributesNameTheFix() {
    compile("<svg><rect t:fill=\"s\"/></svg>").assertError("t.html:1:", "t:fill is not allowed", "a CSS variable");
    compile("<svg><rect t:stroke=\"s\"/></svg>")
        .assertError("t.html:1:", "t:stroke is not allowed", "a CSS variable");
  }

  @Test
  void computedTargetNamesTheFix() {
    compile("<a href=\"/x\" t:target=\"s\">x</a>")
        .assertError("t.html:1:", "t:target is not allowed", "choose between literals");
  }

  @Test
  void rawStringOutputNamesTheSanitizer() {
    compile("<p t:utext=\"s\">x</p>")
        .assertError("t.html:1:", "t:utext needs a value of type dev.chainguard.thwart.runtime.SafeHtml", "html.sanitize(...)",
            "guide section 4.10");
    compile("<p>[(${s})]</p>").assertError("t.html:1:", "[(...)] needs a value of type", "html.sanitize(...)");
  }

  @Test
  void postFormActionThatIsNotALinkNamesTheFix() {
    compile("<form method=\"post\" t:action=\"s\"></form>")
        .assertError("t.html:1:", "t:action is not allowed", "same-origin link", "omit the action");
  }

  @Test
  void scriptValuesOutsideThePreludeNameTheFix() {
    for (String script : new String[] {
      "var a = `[[${s}]]`;", "var t = [[${s}]]; var u = `a ${t} [[${s}]]`;", "var r = [[${s}]]; var q = /a[[${s}]]/;",
      "var a = 1; // [[${s}]]\n",
    }) {
      compile("<script>" + script + "</script>")
          .assertError("t.html:1:", "is only allowed as the whole value of a declaration at the top of the script");
    }
    compile("<script type=\"application/json\">{\"a\": \"x[[${s}]]\"}</script>")
        .assertError("t.html:1:", "inside a quoted string of a JSON <script>", "remove the quotes");
  }

  @Test
  void parseErrorsMigrateDoesNotRewriteNameTheFix() {
    compile("<p>a<div>b</div>c</p>").assertError("t.html:1:", "use a <div> instead of the <p>");
    compile("<table><tr><td>x</td></tr><span>y</span></table>")
        .assertError("t.html:1:", "out of the table", "put it in a <td> or <th>");
    Compilation.builder()
        .template("t.html", "<!DOCTYPE html>\n<html xmlns:t=\"https://thwart.chainguard.dev\">\n<head><title>T</title></head>\n"
            + "<link rel=\"stylesheet\" href=\"/a.css\">\n<body>x</body>\n</html>\n")
        .compile()
        .assertError("t.html:4:", "between </head> and <body>", "inside the <head> or inside the <body>");
  }

  @Test
  void elementBeforeALayoutCallNamesTheFix() {
    compileWithLayout("<t:block xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\">\n"
            + "<link rel=\"stylesheet\" href=\"/r.css\">\n"
            + "<t:block t:replace=\"~{fragments/layout :: layout(null, ~{::body})}\">\n"
            + "<body t:remove=\"tag\"><p t:text=\"s\">x</p></body>\n</t:block>\n</t:block>\n")
        .assertError("page.html:3:", "a second <html>", "before a layout call",
            "move it into a <head t:remove=\"tag\"> in the page and pass ~{::head}");
  }

  @Test
  void elementBeforeALayoutWithNoHeadParameterNamesTheParameterToAdd() {
    String noHead = "<!DOCTYPE html>\n"
        + "<html xmlns:t=\"https://thwart.chainguard.dev\" lang=\"en\" t:fragment=\"layout(Fragment content)\">\n"
        + "<head>\n<title>T</title>\n</head>\n"
        + "<body>\n<t:block t:replace=\"content\"></t:block>\n</body>\n</html>\n";
    Compilation.builder()
        .template("fragments/layout.html", noHead)
        .template("page.html", "<t:block xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\">\n"
            + "<link rel=\"stylesheet\" href=\"/r.css\">\n"
            + "<t:block t:replace=\"~{fragments/layout :: layout(~{::body})}\">\n"
            + "<body t:remove=\"tag\"><p t:text=\"s\">x</p></body>\n</t:block>\n</t:block>\n")
        .compile()
        .assertError("page.html:3:", "a second <html>", "if the layout takes none, give it a Fragment head parameter",
            "<t:block t:replace=\"head\"></t:block> in its <head>");
  }

  @Test
  void elementMovedIntoThePassedHeadCompiles() {
    compileWithLayout("<t:block xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\">\n"
            + "<t:block t:replace=\"~{fragments/layout :: layout(~{::head}, ~{::body})}\">\n"
            + "<head t:remove=\"tag\"><link rel=\"stylesheet\" href=\"/r.css\"></head>\n"
            + "<body t:remove=\"tag\"><p t:text=\"s\">x</p></body>\n</t:block>\n</t:block>\n")
        .assertSuccess();
  }

  @Test
  void nestedBodyNamesTheFix() {
    compileWithLayout("<t:block xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s\">\n"
            + "<t:block t:replace=\"~{fragments/layout :: layout(null, ~{::body})}\">\n"
            + "<body class=\"x\"><p t:text=\"s\">x</p></body>\n</t:block>\n</t:block>\n")
        .assertError("page.html:2:", "a second <body>", "t:remove=\"tag\"");
  }

  @Test
  void recursiveRichTextFragmentNamesTheSanitizer() {
    String rich = "<!DOCTYPE html>\n<html xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"java.util.List\">\n<body>\n"
        + "<t:block t:fragment=\"nodes(List<String> nodes)\"><t:block t:each=\"n : nodes\" t:switch=\"n\">"
        + "<t:block t:case=\"'#text'\" t:text=\"n\"></t:block>"
        + "<p t:case=\"'p'\"><t:block t:replace=\"~{fragments/rich :: nodes(nodes)}\"></t:block></p>"
        + "<b t:case=\"'b'\"><t:block t:replace=\"~{fragments/rich :: nodes(nodes)}\"></t:block></b>"
        + "</t:block></t:block>\n</body>\n</html>\n";
    Compilation.builder()
        .template("fragments/rich.html", rich)
        .template("page.html", "<!DOCTYPE html>\n<html xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"java.util.List\""
            + " t:params=\"List<String> text\">\n<body>\n"
            + "<div><t:block t:replace=\"~{fragments/rich :: nodes(text)}\"></t:block></div>\n</body>\n</html>\n")
        .compile()
        .assertError("page.html:4:", "the fragment calls itself", "t:utext=\"html.sanitize(...)\"", "guide section 4.10");
  }
}
