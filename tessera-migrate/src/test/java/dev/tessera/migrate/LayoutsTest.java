package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Layout markup with a second {@code <body>}, {@code <head>} or {@code <html>} (#409): what is
 * rewritten, what is only reported, and what is left alone. The goldens {@code jobsearch} and
 * {@code spring-petclinic-boot} cover the bench shapes end to end.
 */
class LayoutsTest {

  private static final String LAYOUT = "<!DOCTYPE html>\n"
      + "<html xmlns:t=\"https://tessera.dev\" lang=\"en\" t:fragment=\"layout(Fragment content, Fragment head)\">\n"
      + "<head><title>T</title><t:block t:replace=\"head\"></t:block></head>\n"
      + "<body class=\"app\">\n<div class=\"container\"><t:block t:replace=\"content\"></t:block></div>\n</body>\n"
      + "</html>\n";

  private static Layouts.Result rewrite(String... fileAndSource) {
    Map<String, String> templates = new LinkedHashMap<>();
    for (int i = 0; i < fileAndSource.length; i += 2) {
      templates.put(fileAndSource[i], fileAndSource[i + 1]);
    }
    Layouts.Result result = Layouts.rewrite(templates);
    assertEquals(result.templates(), Layouts.rewrite(result.templates()).templates(), "not idempotent");
    return result;
  }

  private static List<String> messages(Layouts.Result result) {
    return result.items().stream().map(i -> i.file() + ":" + i.line() + ": " + i.message()).toList();
  }

  private static void assertMessages(Layouts.Result result, String... prefixes) {
    List<String> messages = messages(result);
    assertEquals(prefixes.length, messages.size(), messages.toString());
    for (int i = 0; i < prefixes.length; i++) {
      assertTrue(messages.get(i).startsWith(prefixes[i]), messages.toString());
    }
  }

  @Test
  void bodyPassedIntoTheLayoutsBodyGetsRemoveTag() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page.replace("<body>", "<body t:remove=\"tag\">"), r.templates().get("page.html"));
    assertEquals(LAYOUT, r.templates().get("layout.html"));
    assertMessages(r, "page.html:2: rewritten: added `t:remove=\"tag\"` to the `<body>`");
  }

  @Test
  void namedArgumentAndHeadAreResolvedToo() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"layout :: layout(head=~{this :: head}, content=~{::body})\">\n"
        + "<head><link rel=\"stylesheet\" href=\"/p.css\"></head>\n<body>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page.replace("<head>", "<head t:remove=\"tag\">").replace("<body>", "<body t:remove=\"tag\">"),
        r.templates().get("page.html"));
    assertMessages(r, "page.html:2: rewritten: added `t:remove=\"tag\"` to the `<head>`",
        "page.html:3: rewritten: added `t:remove=\"tag\"` to the `<body>`");
  }

  @Test
  void alreadyCorrectLayoutIsUntouched() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:remove=\"tag\">\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r);
  }

  @Test
  void fragmentThatInsertsOutsideABodyIsNotRewritten() {
    String card = "<div xmlns:t=\"https://tessera.dev\"><section t:fragment=\"card(Fragment content)\">"
        + "<div t:replace=\"content\"></div></section></div>\n";
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{card :: card(~{::body})}\">\n"
        + "<body>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("card.html", card, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r);
  }

  @Test
  void bodyWithAttributesIsReportedNotRewritten() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body class=\"home\">\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r, "page.html:2: `<body class=\"home\">` is passed to `layout :: layout`, which inserts it inside its own `<body>`");
  }

  @Test
  void bodyWithTemplateAttributesIsReportedNotRewritten() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:class=\"'app'\">\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r, "page.html:2: `<body t:class=\"'app'\">` is passed to");
  }

  @Test
  void attributesTheLayoutsBodyAlreadyHasAreDropped() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body class=\"app\">\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page.replace("<body class", "<body t:remove=\"tag\" class"), r.templates().get("page.html"));
  }

  @Test
  void attributeTheLayoutSetsDynamicallyIsReportedNotDropped() {
    // t:class replaces the static class and omits it when null, so the page's class may be merged.
    String layout = LAYOUT.replace("<body class=\"app\">", "<body class=\"app\" t:class=\"null\">");
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body class=\"app\">\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", layout, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r, "page.html:2: `<body class=\"app\">` is passed to `layout :: layout`");
    assertTrue(r.items().get(0).message().contains("`String bodyClass` and `<body t:class=\"bodyClass\">`"),
        messages(r).toString());
  }

  @Test
  void layoutPathWithALeadingSlashIsResolved() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{/fragments/layout :: layout(~{::body}, null)}\">\n"
        + "<body>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("fragments/layout.html", LAYOUT, "page.html", page);
    assertEquals(page.replace("<body>", "<body t:remove=\"tag\">"), r.templates().get("page.html"));
  }

  /** The compiler's verifier checks the composed page; migrate doesn't report what it can't resolve (#595). */
  @Test
  void unknownLayoutIsLeftToTheCompiler() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{missing :: layout(~{::body})}\">\n"
        + "<body>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r);
  }

  @Test
  void innerBodyIsDeletedOnlyWithoutNewAttributes() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:remove=\"tag\">\n  <body>\n  <h1>Hi</h1>\n  </body>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page.replace("  <body>\n", "").replace("  </body>\n", ""), r.templates().get("page.html"));
    assertMessages(r, "page.html:3: rewritten: deleted the tags of a `<body>` inside a `<body>`");

    String same = page.replace("  <body>", "  <body class=\"app\">");
    r = rewrite("layout.html", LAYOUT, "page.html", same);
    assertEquals(page.replace("  <body>\n", "").replace("  </body>\n", ""), r.templates().get("page.html"));

    String other = page.replace("  <body>", "  <body class=\"bg-light\">");
    r = rewrite("layout.html", LAYOUT, "page.html", other);
    assertEquals(other, r.templates().get("page.html"));
    assertMessages(r, "page.html:3: `<body class=\"bg-light\">` is inside a `<body>`");
  }

  @Test
  void headInsideBodyIsReported() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:remove=\"tag\">\n<head><link rel=\"stylesheet\" href=\"/p.css\"></head>\n<h1>Hi</h1>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r, "page.html:3: `<head>` inside a `<body>`");
  }

  private static final String NAVBAR = "<div xmlns:t=\"https://tessera.dev\">\n"
      + "<t:block t:fragment=\"navbar(String user)\">\n<html lang=\"en\">\n<head>\n  <title>T</title>\n</head>\n"
      + "<body>\n  <nav t:text=\"user\">u</nav>\n</body>\n</html>\n</t:block>\n</div>\n";

  @Test
  void fragmentThatWritesADocumentIntoBodiesIsUnwrapped() {
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:remove=\"tag\">\n<t:block t:replace=\"~{navbar :: navbar('ann')}\"></t:block>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "navbar.html", NAVBAR, "page.html", page);
    // Only the tags a browser ignores go; the head's elements stay where a browser put them.
    assertEquals("<div xmlns:t=\"https://tessera.dev\">\n<t:block t:fragment=\"navbar(String user)\">\n"
        + "  <title>T</title>\n  <nav t:text=\"user\">u</nav>\n</t:block>\n</div>\n", r.templates().get("navbar.html"));
    assertMessages(r, "navbar.html:3: rewritten: fragment `navbar :: navbar` wrote a whole document");
    assertTrue(r.items().get(0).message().contains("what the `<head>` held (`<title>`)"), messages(r).toString());
  }

  @Test
  void fragmentThatWritesADocumentIsReportedUnlessEveryInsertIsInABody() {
    String page = "<div xmlns:t=\"https://tessera.dev\"><t:block t:replace=\"~{navbar :: navbar('ann')}\"></t:block></div>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "navbar.html", NAVBAR, "page.html", page);
    assertEquals(NAVBAR, r.templates().get("navbar.html"));
    assertMessages(r, "navbar.html:3: fragment `navbar :: navbar` writes a whole document");

    // lang="fr" would be merged into a page <html> that lacks it.
    String fr = NAVBAR.replace("lang=\"en\"", "lang=\"fr\"");
    String inBody = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, null)}\">\n"
        + "<body t:remove=\"tag\">\n<t:block t:replace=\"~{navbar :: navbar('ann')}\"></t:block>\n</body>\n</html>\n";
    r = rewrite("layout.html", LAYOUT, "navbar.html", fr, "page.html", inBody);
    assertEquals(fr, r.templates().get("navbar.html"));
    assertMessages(r, "navbar.html:3: fragment `navbar :: navbar` writes a whole document");
  }

  @Test
  void fragmentThatWritesADocumentAndIsPassedAsAnArgumentIsReported() {
    // The layout may insert an argument anywhere, so the page body inserts don't cover it.
    String page = "<html xmlns:t=\"https://tessera.dev\" t:replace=\"~{layout :: layout(~{::body}, ~{navbar :: navbar('x')})}\">\n"
        + "<body t:remove=\"tag\">\n<t:block t:replace=\"~{navbar :: navbar('ann')}\"></t:block>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "navbar.html", NAVBAR, "page.html", page);
    assertEquals(NAVBAR, r.templates().get("navbar.html"));
    assertMessages(r, "navbar.html:3: fragment `navbar :: navbar` writes a whole document");
  }

  /**
   * Parse errors migrate doesn't rewrite are the compiler's to report, with file:line:col and the
   * fix (#595): an element before the layout's doctype, ...
   */
  @Test
  void elementsBeforeTheLayoutAreLeftToTheCompiler() {
    String page = "<t:block xmlns:t=\"https://tessera.dev\">\n<link rel=\"stylesheet\" href=\"/p.css\">\n"
        + "<t:block t:fragment=\"skipped(String s)\"><p t:text=\"s\">s</p></t:block>\n"
        + "<t:block t:replace=\"~{layout :: layout(~{::body}, null)}\">\n<body t:remove=\"tag\"><h1>Hi</h1></body>\n"
        + "</t:block>\n</t:block>\n";
    Layouts.Result r = rewrite("layout.html", LAYOUT, "page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r);
  }

  /** ... an element between head and body, a block in a {@code <p>} and content directly in a table. */
  @Test
  void otherParseErrorsAreLeftToTheCompiler() {
    String page = "<!DOCTYPE html>\n<html>\n<head><title>T</title></head>\n<script src=\"/a.js\"></script>\n"
        + "<body>\n<p>Intro\n<div>block</div>\n</p>\n<p>fine <span>x</span></p>\n"
        + "<p>open\n<div>no end tag, so no error</div>\n"
        + "<table>\n<h3>Title</h3>\n<t:block t:each=\"r : rows\"><tr><td>x</td></tr></t:block>\n"
        + "<tr t:replace=\"~{rows :: row}\"></tr>\n</table>\n</body>\n</html>\n";
    Layouts.Result r = rewrite("page.html", page);
    assertEquals(page, r.templates().get("page.html"));
    assertMessages(r);
  }
}
