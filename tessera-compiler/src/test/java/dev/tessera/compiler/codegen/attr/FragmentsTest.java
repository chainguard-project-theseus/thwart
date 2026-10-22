package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Fragment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** t:fragment, t:insert and t:replace end to end through javac and the real processor. */
class FragmentsTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** Fragments whose param types the tests use, plus the petclinic-style layout. */
  private static final String CARDS =
      "<div " + NS + " t:import=\"com.example.*\">"
          + "<section t:fragment=\"card(String title, Fragment content)\">"
          + "<h3>T</h3><div t:replace=\"content\">c</div></section>"
          + "<section t:fragment=\"user(User user)\">u</section>"
          + "<i t:fragment=\"plain\">plain</i>"
          + "</div>";

  private static final String LAYOUT =
      "<!DOCTYPE html>\n"
          + "<html " + NS + " t:fragment=\"layout(Fragment template, String menu)\">\n"
          + "<head><title>PetClinic</title></head>\n"
          + "<body><nav>nav</nav><div class=\"container\"><div t:replace=\"${template}\">content</div></div></body>\n"
          + "</html>\n";

  private static Compilation.Builder base() {
    return Compilation.builder()
        .java("com.example.User", "package com.example; public record User(String name) {}")
        .template("fragments/cards.html", CARDS);
  }

  private static Compilation compile(Compilation.Builder b) {
    Compilation c = b.compile();
    return c;
  }

  private static Fragment text(String s) {
    return (ctx, out) -> out.write(s);
  }

  @Test
  void sameFileInsertKeepsHostAndReplaceDropsIt() {
    Compilation c =
        compile(
                Compilation.builder()
                    .template(
                        "page.html",
                        "<main " + NS + "><i t:fragment=\"hi\"><b>hi</b></i>"
                            + "<div class=\"a\" t:insert=\"~{:: hi}\">x</div>"
                            + "<div t:replace=\"~{this :: hi}\">x</div>"
                            + "<div t:insert=\":: hi\">x</div></main>"))
            .assertSuccess();
    assertEquals(
        "<main><i><b>hi</b></i><div class=\"a\"><i><b>hi</b></i></div><i><b>hi</b></i><div><i><b>hi</b></i></div></main>",
        c.render("page", Map.of()));
  }

  @Test
  void parameterizedFragmentIsSkippedInPlace() {
    Compilation c = compile(base()).assertSuccess();
    // card and user need arguments; plain has none and renders in place.
    assertEquals("<div><i>plain</i></div>", c.render("fragments/cards", Map.of()));
  }

  @Test
  void parameterizedFragmentInPlaceDoesNotCompileItsAttributesInTheTemplateScope() {
    // `content` is only a fragment parameter; compiled in the page scope it would be unknown.
    Compilation c = compile(base()).assertSuccess();
    assertTrue(c.generatedSource("tessera.generated.fragments.CardsTemplate").contains("fragment_card("));
  }

  @Test
  void crossFilePositionalAndNamedArguments() {
    Compilation c =
        compile(
                base()
                    .template(
                        "page.html",
                        "<main " + NS + " t:params=\"Fragment body\">"
                            + "<div t:replace=\"~{fragments/cards :: card('A', ~{::p})}\">x</div>"
                            + "<div t:replace=\"fragments/cards :: card(content=body, title='B')\">x</div>"
                            + "<div t:insert=\"~{fragments/cards :: plain}\">x</div>"
                            + "<div t:insert=\"~{fragments/cards :: plain()}\">x</div>"
                            + "<p>para</p></main>"))
            .assertSuccess();
    assertEquals(
        "<main><section><h3>T</h3><p>para</p></section><section><h3>T</h3>BODY</section>"
            + "<div><i>plain</i></div><div><i>plain</i></div><p>para</p></main>",
        c.render("page", Map.of("body", text("BODY"))));
  }

  @Test
  void argumentsOfAssignableTypesCompile() {
    compile(
            base()
                .template(
                    "page.html",
                    "<main " + NS + " t:import=\"com.example.*\" t:params=\"User u, List<User> us\">"
                        + "<div t:replace=\"~{fragments/cards :: user(u)}\"></div>"
                        + "<div t:replace=\"~{fragments/cards :: user(us[0])}\"></div>"
                        + "<div t:replace=\"~{fragments/cards :: user(null)}\"></div>"
                        + "</main>"))
        .assertSuccess();
  }

  @Test
  void petclinicLayoutPattern() {
    String page =
        "<!DOCTYPE html>\n"
            + "<html " + NS + " t:params=\"Fragment extra\""
            + " t:replace=\"~{fragments/layout :: layout(~{::body}, 'owners')}\">\n"
            + "<body><h2>Owners</h2><p t:insert=\"extra\">x</p></body>\n"
            + "</html>\n";
    Compilation c =
        compile(Compilation.builder().template("fragments/layout.html", LAYOUT).template("owners/list.html", page))
            .assertSuccess();
    assertEquals(
        "<!DOCTYPE html>\n"
            + "<html>\n"
            + "<head><title>PetClinic</title></head>\n"
            + "<body><nav>nav</nav><div class=\"container\"><body><h2>Owners</h2><p>EXTRA</p></body></div></body>\n"
            + "</html>\n",
        c.render("owners/list", Map.of("extra", text("EXTRA"))));
    // The layout itself is a parameterized fragment on the root element: renders as the doctype only.
    assertEquals("<!DOCTYPE html>\n\n", c.render("fragments/layout", Map.of()));
  }

  @Test
  void nullFragmentValueInsertsNothing() {
    Compilation c =
        compile(Compilation.builder().template("page.html", "<main " + NS + " t:params=\"Fragment f\"><p t:insert=\"f\">x</p></main>"))
            .assertSuccess();
    assertEquals("<main><p></p></main>", c.render("page", Map.of()));
  }

  @Test
  void fragmentParamsNamedLikePackagesOrRenderParamsWork() {
    Compilation c =
        compile(
                Compilation.builder()
                    .template(
                        "page.html",
                        "<main " + NS + " t:params=\"Fragment f\">"
                            + "<b t:fragment=\"x(Fragment java, Fragment ctx, Fragment out, Fragment dev)\">"
                            + "<i t:replace=\"java\"></i><i t:replace=\"ctx\"></i><i t:replace=\"out\"></i><i t:replace=\"dev\"></i></b>"
                            + "<div t:replace=\"~{:: x(f, f, f, f)}\"></div></main>"))
            .assertSuccess();
    assertEquals("<main><b>FFFF</b></main>", c.render("page", Map.of("f", text("F"))));
  }

  @Test
  void textInFragmentsUsesOnlyArguments() {
    // Needs t:text (#11); skipped until it lands.
    Compilation c =
        compile(
            base()
                .template(
                    "page.html",
                    "<main " + NS + " t:import=\"com.example.*\" t:params=\"User u\">"
                        + "<b t:fragment=\"name(User user)\" t:text=\"user.name\">n</b>"
                        + "<div t:replace=\"~{:: name(u)}\"></div><div t:replace=\"~{:: name(user=u)}\"></div></main>"));
    c.assertSuccess();
    Object u = c.newInstance("com.example.User", "<Ann>");
    assertEquals("<main><b>&lt;Ann&gt;</b><b>&lt;Ann&gt;</b></main>", c.render("page", Map.of("u", u)));
  }

  @Test
  void generatedCodeHasNoReflection() {
    Compilation c =
        compile(Compilation.builder().template("fragments/layout.html", LAYOUT)
            .template("p.html", "<html " + NS + " t:replace=\"~{fragments/layout :: layout(~{::body}, 'x')}\"><body>b</body></html>"))
            .assertSuccess();
    for (String name : List.of("tessera.generated.PTemplate", "tessera.generated.fragments.LayoutTemplate")) {
      String src = c.generatedSource(name);
      for (String banned : List.of("java.lang.reflect", "java.lang.invoke", "MethodHandle", "Class.forName")) {
        assertFalse(src.contains(banned), name + " contains " + banned);
      }
    }
  }

  // ---------------------------------------------------------------- negative tests

  private static Compilation page(String body) {
    return compile(
        base().template("page.html", "<main " + NS + " t:import=\"com.example.*\" t:params=\"User u\">\n" + body + "</main>"));
  }

  @Test
  void callerVariablesAreNotVisibleInsideAFragment() {
    Compilation c =
        page("<b t:fragment=\"f(String s)\"><i t:insert=\"u\"></i></b><div t:replace=\"~{:: f('x')}\"></div>\n");
    c.assertError("page.html:2:42", "unknown identifier 'u'");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void parameterlessFragmentInPlaceDoesNotSeeTemplateParams() {
    page("<b t:fragment=\"f\"><i t:insert=\"u\"></i></b>\n").assertError("page.html:2:32", "unknown identifier 'u'");
  }

  @Test
  void argumentTypeMismatchNamesTheCalleeSignature() {
    page("<div t:replace=\"~{fragments/cards :: card(1, ~{::b})}\"></div><b>b</b>\n")
        .assertError(
            "page.html:2:43",
            "argument 1 ('title') of fragment fragments/cards :: card(java.lang.String title,"
                + " dev.tessera.runtime.Fragment content)",
            "but the argument has type int");
    page("<div t:replace=\"~{fragments/cards :: user('ann')}\"></div>\n")
        .assertError("page.html:2:43", "fragments/cards :: user(com.example.User user)", "java.lang.String");
    page("<div t:replace=\"~{fragments/cards :: card(title='a', content='b')}\"></div>\n")
        .assertError("argument 2 ('content')", "has type dev.tessera.runtime.Fragment", "java.lang.String");
    page("<div t:replace=\"~{fragments/cards :: user(~{::b})}\"></div><b>b</b>\n")
        .assertError("page.html:2:43", "has type com.example.User, but a fragment ~{...} was passed");
  }

  @Test
  void wrongArityNamesTheCalleeSignature() {
    page("<div t:replace=\"~{fragments/cards :: card('a')}\"></div>\n")
        .assertError(
            "page.html:2:17",
            "wrong number of arguments for fragment fragments/cards :: card(java.lang.String title,"
                + " dev.tessera.runtime.Fragment content): expected 2 but got 1");
    page("<div t:replace=\"~{fragments/cards :: user}\"></div>\n")
        .assertError("page.html:2:17", "fragments/cards :: user(com.example.User user)", "expected 1 but got 0");
    page("<div t:replace=\"~{fragments/cards :: plain(u)}\"></div>\n")
        .assertError("fragments/cards :: plain()", "expected 0 but got 1");
    page("<div t:replace=\"~{fragments/cards :: card(title='a')}\"></div>\n")
        .assertError("page.html:2:17", "missing argument 'content' for fragment fragments/cards :: card(");
    page("<div t:replace=\"~{fragments/cards :: user(name=u)}\"></div>\n")
        .assertError("page.html:2:43", "unknown argument 'name' for fragment fragments/cards :: user(");
    page("<div t:replace=\"~{fragments/cards :: user(user=u, user=u)}\"></div>\n")
        .assertError("duplicate argument 'user'");
  }

  @Test
  void unknownTemplateAndFragment() {
    page("<div t:insert=\"~{fragments/nope :: card}\"></div>\n")
        .assertError("page.html:2:16", "unknown template 'fragments/nope'");
    page("<div t:insert=\"~{fragments/cards :: nope}\"></div>\n")
        .assertError("page.html:2:16", "template 'fragments/cards' has no fragment 'nope'", "card(java.lang.String title");
    page("<div t:insert=\"~{:: nope}\"></div>\n").assertError("page.html:2:16", "template 'page' has no fragment 'nope'");
  }

  @Test
  void templatePathsCannotEscapeTheTemplatesRoot() {
    for (String tpl : List.of("../fragments/cards", "fragments/../fragments/cards", "/fragments/cards", "./fragments/cards", "fragments//cards")) {
      page("<div t:insert=\"~{" + tpl + " :: plain}\"></div>\n")
          .assertError("page.html:2:16", "template name '" + tpl + "' must be a path relative to the templates root");
    }
    assertFalse(InsertReplaceProcessor.insideRoot(".."));
    assertTrue(InsertReplaceProcessor.insideRoot("a/b.c/d-e"));
  }

  @Test
  void selectedElementErrors() {
    page("<div t:replace=\"~{fragments/cards :: card('a', ~{::nav})}\"></div>\n")
        .assertError("page.html:2:48", "no fragment or element named 'nav'");
    page("<div t:replace=\"~{fragments/cards :: card('a', ~{::main})}\"></div>\n")
        .assertError("page.html:2:48", "would include itself");
    page("<div t:replace=\"~{fragments/cards :: card('a', ~{::b('x')})}\"></div><b>b</b>\n")
        .assertError("selects an element, which takes no arguments");
  }

  @Test
  void insertOfANonFragmentValueIsAnError() {
    page("<div t:insert=\"u\"></div>\n")
        .assertError("page.html:2:16", "needs a fragment reference", "com.example.User");
    page("<div t:replace=\"'<script>'\"></div>\n").assertError("page.html:2:17", "java.lang.String");
    page("<div t:insert=\"\"></div>\n").assertError("page.html:2:6", "needs a fragment reference");
  }

  @Test
  void insertIntoScriptOrStyleIsRejected() {
    // The fragment's markup would otherwise run as live JavaScript or CSS.
    page("<script t:insert=\"~{fragments/cards :: plain}\"></script>\n")
        .assertError(
            "page.html:2:9", "t:insert is only allowed on elements whose content is HTML text, not <script> or <style>");
    page("<style t:insert=\"~{fragments/cards :: plain}\"></style>\n")
        .assertError(
            "page.html:2:8", "t:insert is only allowed on elements whose content is HTML text, not <script> or <style>");
  }

  @Test
  void insertAndReplaceOnHtmlHostsStillRender() {
    Compilation c =
        page("<div t:insert=\"~{fragments/cards :: plain}\">x</div>"
                + "<p t:replace=\"~{fragments/cards :: plain}\">x</p>"
                + "<script t:replace=\"~{fragments/cards :: plain}\">x</script>")
            .assertSuccess();
    // t:replace swaps the whole element, so a <script> host is not a script context.
    assertEquals(
        "<main>\n<div><i>plain</i></div><i>plain</i><i>plain</i></main>",
        c.render("page", Map.of("u", c.newInstance("com.example.User", "a"))));
  }
}
