package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The CSRF token goes only into a POST form that posts to the page's origin (#285): no action, a
 * static one same-origin by shape, or a same-origin link. Anything else computed is a build error.
 */
class CsrfActionOriginTest {
  private static final String INPUT = "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />";

  private static TestForms withToken() {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    return forms;
  }

  private static Compilation compile(String params, String formAttrs) {
    return Compilation.builder()
        .template(
            "form.html",
            "<div xmlns:t=\"https://tessera.dev\"" + (params == null ? "" : " t:params=\"" + params + "\"")
                + "><form " + formAttrs + ">x</form></div>")
        .compile()
        .assertSuccess();
  }

  private static String render(Compilation c, Map<String, Object> model) {
    return c.render("form", model, ctx(withToken()));
  }

  private static String render(String formAttrs) {
    return render(compile(null, formAttrs), Map.of());
  }

  private static String renderWith(Compilation c, String name, Object value) {
    Map<String, Object> model = new HashMap<>();
    model.put(name, value);
    return render(c, model);
  }

  @Test
  void relativeStaticActionsGetToken() {
    assertEquals("<div><form method=\"post\">" + INPUT + "x</form></div>", render("method=\"post\""));
    for (String action :
        new String[] {"", "/x", "x", "?q=1", "#", "/a:b", "./x", "../x", " /x", "/x?a=1&amp;b=2", "/x?a&b", "/a&sol;b"}) {
      String out = render("method=\"post\" action=\"" + action + "\"");
      assertEquals("<div><form method=\"post\" action=\"" + action + "\">" + INPUT + "x</form></div>", out, action);
    }
  }

  /** Negative: a static action with a scheme, or a protocol-relative one, gets no token. */
  @Test
  void crossOriginStaticActionsGetNoToken() {
    for (String action :
        new String[] {
          "https://other/", "HTTP://other/", "//other/", "/\\other/", "\\\\other/", "\\/other/",
          " //other/", "/&#9;/other/", "&#10;//other", "mailto:a@b", "javascript:x",
          // Named references the parser keeps but a browser decodes (/ : tab).
          "&sol;&sol;evil.example/", "/&sol;evil/", "https&colon;//evil/", "&Tab;//evil/", "/&Tab;/evil/",
          "&bsol;&bsol;evil/", "&sol;&#47;evil/",
          // A browser resolves this against the page (same scheme), but any scheme counts as another
          // origin here: it fails closed.
          "https:other"
        }) {
      String out = render("method=\"post\" action=\"" + action + "\"");
      assertFalse(out.contains("_csrf"), action + ": " + out);
    }
  }

  @Test
  void relativeLinkActionsGetToken() {
    assertEquals(
        "<div><form method=\"post\" action=\"/x\">" + INPUT + "x</form></div>",
        render("method=\"post\" t:action=\"@{/x}\""));
    assertEquals(
        "<div><form method=\"post\" action=\"/o/1\">" + INPUT + "x</form></div>",
        render("method=\"post\" t:action=\"@{/o/{id}(id=1)}\""));
  }

  /**
   * Negative: a computed action of a static POST form that the compiler cannot prove same-origin is
   * a build error (closed shape): a value instead of a link (#285, #386), an absolute or
   * protocol-relative link (#387), or a relative link.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "t:action=\"u\"",
        "t:attr=\"action=u\"",
        "t:attr=\"title=u, ACTION=u\"",
        "t:action=\"t\"",
        "t:action=\"u != null ? @{/a} : @{/b}\"",
        "t:action=\"@{https://evil.example/x}\"",
        "t:action=\"@{//evil.example/x}\"",
        "t:action=\"@{/\\evil.example/x}\"",
        "t:attr=\"action=@{https://evil.example/x}\"",
        "t:action=\"@{x}\"",
        "t:action=\"@{?page=2}\"",
      })
  void unprovenComputedActionFails(String attr) {
    Compilation c =
        Compilation.builder()
            .template(
                "form.html",
                "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.TrustedUrl\""
                    + " t:params=\"String u, TrustedUrl t\"><form method=\"post\" " + attr + ">x</form></div>")
            .compile();
    c.assertError("form.html:1:", "is not allowed", "the CSRF token");
    assertNull(c.generatedSource("tessera.generated.FormTemplate"), "nothing may be generated");
  }

  @Test
  void errorNamesTheFix() {
    Compilation c = Compilation.builder()
        .template("form.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u\">\n"
            + "<form method=\"post\" t:action=\"u\">x</form></div>")
        .compile();
    c.assertError("form.html:2:21:", "t:action is not allowed: a POST form gets the CSRF token, which then goes to its"
        + " action; use a same-origin link such as @{/items/{id}(id=${item.id})}; omit the action to post to the"
        + " page's own URL, or write a static action=\"https://...\" if another site is meant (it gets no token)");
    Compilation abs = Compilation.builder()
        .template("form.html", "<div xmlns:t=\"https://tessera.dev\">\n"
            + "<form method=\"post\" t:action=\"@{https://www.example.com/owners}\">x</form></div>")
        .compile();
    abs.assertError("form.html:2:", "t:action is not allowed: the action of a POST form, which gets the CSRF token,"
        + " must be a same-origin path starting with a single '/', not @{https://www.example.com/owners}");
  }

  /** A static GET form never gets a token, whatever its action (unchanged). */
  @Test
  void methodStillRules() {
    Compilation c = compile("String u", "method=\"get\" t:action=\"u\"");
    assertFalse(renderWith(c, "u", "/x").contains("_csrf"));
    assertFalse(render("action=\"/x\"").contains("_csrf"));
  }

  @Test
  void linkActionInsideLoopEvaluatedPerIteration() {
    Compilation c =
        Compilation.builder()
            .template(
                "form.html",
                "<div xmlns:t=\"https://tessera.dev\" t:params=\"java.util.List<String> us\">"
                    + "<form t:each=\"u : us\" method=\"post\" t:action=\"@{/o/{u}(u=${u})}\">x</form></div>")
            .compile()
            .assertSuccess();
    String out = render(c, Map.of("us", List.of("a", "https://evil/")));
    assertTrue(out.startsWith("<div><form method=\"post\" action=\"/o/a\">" + INPUT + "x</form>"
        + "<form method=\"post\" action=\"/o/https"), out);
    assertEquals(2, out.split("_csrf", -1).length - 1, out);
  }

  /**
   * {@code hx-boost} changes nothing (#585): a boosted POST form's link action passes the same
   * closed-shape check as any other, so the form keeps its token.
   */
  @Test
  void boostedFormLinkActionGetsToken() {
    Compilation c =
        Compilation.builder()
            .template(
                "form.html",
                "<div xmlns:t=\"https://tessera.dev\"><section hx-boost=\"true\">"
                    + "<form method=\"post\" t:action=\"@{/o/{id}(id=1)}\">x</form></section>"
                    + "<form hx-boost=\"true\" method=\"post\" t:attr=\"action=@{/x}\">y</form></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><section hx-boost=\"true\"><form method=\"post\" action=\"/o/1\">" + INPUT + "x</form></section>"
            + "<form hx-boost=\"true\" method=\"post\" action=\"/x\">" + INPUT + "y</form></div>",
        render(c, Map.of()));
  }

  @Test
  void noTokenProviderNoInput() {
    Compilation c = compile(null, "method=\"post\" t:action=\"@{/x}\"");
    assertEquals(
        "<div><form method=\"post\" action=\"/x\">x</form></div>",
        c.render("form", Map.of(), ctx(new TestForms())));
  }
}
