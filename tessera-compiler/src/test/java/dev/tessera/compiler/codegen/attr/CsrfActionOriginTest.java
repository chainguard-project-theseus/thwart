package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.TrustedUrl;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The CSRF token goes only into a POST form that posts to the page's origin (#285): no action, or
 * a relative one. A static action is checked at build time, a computed one at render time.
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

  /** Negative: an absolute link is not the page's origin by shape. */
  @Test
  void absoluteLinkActionGetsNoToken() {
    String out = render("method=\"post\" t:action=\"@{https://evil.example/x}\"");
    assertEquals("<div><form method=\"post\" action=\"https://evil.example/x\">x</form></div>", out);
  }

  @Test
  void relativeComputedActionsGetToken() {
    Compilation c = compile("String u", "method=\"post\" t:action=\"u\"");
    for (String u : new String[] {"/x", "x", "", "  /x", "/x;jsessionid=1", "?page=2"}) {
      String out = renderWith(c, "u", u);
      assertTrue(out.contains(INPUT), u + ": " + out);
    }
    // A null action omits the attribute: the form posts to the page itself.
    assertEquals("<div><form method=\"post\">" + INPUT + "x</form></div>", renderWith(c, "u", null));
  }

  /** Negative: the data-controlled action from #285 no longer receives the session's token. */
  @Test
  void crossOriginComputedActionsGetNoToken() {
    Compilation c = compile("String u", "method=\"post\" t:action=\"u\"");
    for (String u :
        new String[] {
          "https://evil/", "HTTPS://evil/", "//evil/", "/\\evil/", "\\\\evil/", "\\/evil/", "\t//evil",
          "/\t/evil", "\u0000//evil", " https://evil/", "mailto:a@b", "java\tscript:x", "javascript:x"
        }) {
      String out = renderWith(c, "u", u);
      assertFalse(out.contains("_csrf"), u + ": " + out);
    }
    assertEquals(
        "<div><form method=\"post\" action=\"https://evil.example/steal\">x</form></div>",
        renderWith(c, "u", "https://evil.example/steal"));
  }

  /**
   * Negative: the action is converted to text once, and that text is both checked and written. A
   * value whose {@code toString()} alternates between a relative and an absolute URL must never get
   * the token next to the absolute one, whichever it returns first.
   */
  @Test
  void computedActionIsCheckedAsWritten() {
    for (String attr : new String[] {"t:action=\"u\"", "t:attr=\"action=u\""}) {
      Compilation c = compileFlip("method=\"post\" " + attr);
      assertEquals(
          "<div><form method=\"post\" action=\"https://evil/\">x</form></div>",
          renderWith(c, "u", flip(c, 1, "/x", "https://evil/")),
          attr);
      assertEquals(
          "<div><form method=\"post\" action=\"/x\">" + INPUT + "x</form></div>",
          renderWith(c, "u", flip(c, 0, "/x", "https://evil/")),
          attr);
    }
  }

  /** Negative: the same for a computed method, so a form written as GET never gets the token. */
  @Test
  void computedMethodIsCheckedAsWritten() {
    Compilation c = compileFlip("t:method=\"u\" action=\"/x\"");
    assertEquals(
        "<div><form action=\"/x\" method=\"get\">x</form></div>", renderWith(c, "u", flip(c, 1, "post", "get")));
    assertEquals(
        "<div><form action=\"/x\" method=\"post\">" + INPUT + "x</form></div>",
        renderWith(c, "u", flip(c, 0, "post", "get")));
  }

  /** A form whose parameter {@code u} is a {@code Flip}: its {@code toString()} alternates two values. */
  private static Compilation compileFlip(String formAttrs) {
    return Compilation.builder()
        .java(
            "com.example.Flip",
            "package com.example; @dev.tessera.runtime.TemplateView public class Flip {"
                + " private final String[] values; private int next;"
                + " public Flip(int first, String a, String b) { values = new String[] {a, b}; next = first; }"
                + " @Override public String toString() { return values[next++ % 2]; } }")
        .template(
            "form.html",
            "<div xmlns:t=\"https://tessera.dev\" t:params=\"com.example.Flip u\"><form " + formAttrs
                + ">x</form></div>")
        .compile()
        .assertSuccess();
  }

  /** A {@code Flip} whose first {@code toString()} returns {@code first == 0 ? a : b}. */
  private static Object flip(Compilation c, int first, String a, String b) {
    return c.newInstance("com.example.Flip", first, a, b);
  }

  @Test
  void attrActionIsCheckedToo() {
    Compilation c = compile("String u", "method=\"post\" t:attr=\"action=u\"");
    assertTrue(renderWith(c, "u", "/x").contains(INPUT));
    assertFalse(renderWith(c, "u", "https://evil/").contains("_csrf"));
  }

  /** A TrustedUrl vouches for the scheme, not for being the page's origin. */
  @Test
  void trustedUrlActionIsCheckedToo() {
    Compilation c = compile("TrustedUrl u", "method=\"post\" t:action=\"u\"");
    assertTrue(renderWith(c, "u", TrustedUrl.unchecked("/x")).contains(INPUT));
    String out = renderWith(c, "u", TrustedUrl.unchecked("https://evil/"));
    assertEquals("<div><form method=\"post\" action=\"https://evil/\">x</form></div>", out);
  }

  /** Both method and action computed: the token needs both a POST and a same-origin action. */
  @Test
  void computedMethodAndAction() {
    Compilation c = compile("String m, String u", "t:method=\"m\" t:action=\"u\"");
    Map<String, Object> model = new HashMap<>();
    model.put("m", "post");
    model.put("u", "/x");
    assertEquals("<div><form method=\"post\" action=\"/x\">" + INPUT + "x</form></div>", render(c, model));
    model.put("u", "https://evil/");
    assertEquals("<div><form method=\"post\" action=\"https://evil/\">x</form></div>", render(c, model));
    model.put("m", "get");
    model.put("u", "/x");
    assertEquals("<div><form method=\"get\" action=\"/x\">x</form></div>", render(c, model));
  }

  /** A static GET form never gets a token, whatever its action (unchanged). */
  @Test
  void methodStillRules() {
    Compilation c = compile("String u", "method=\"get\" t:action=\"u\"");
    assertFalse(renderWith(c, "u", "/x").contains("_csrf"));
    assertFalse(render("action=\"/x\"").contains("_csrf"));
  }

  @Test
  void actionInsideLoopEvaluatedPerIteration() {
    Compilation c =
        Compilation.builder()
            .template(
                "form.html",
                "<div xmlns:t=\"https://tessera.dev\" t:params=\"java.util.List<String> us\">"
                    + "<form t:each=\"u : us\" method=\"post\" t:action=\"u\">x</form></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><form method=\"post\" action=\"/a\">" + INPUT + "x</form>"
            + "<form method=\"post\" action=\"https://evil/\">x</form></div>",
        render(c, Map.of("us", List.of("/a", "https://evil/"))));
  }

  /**
   * A boosted form's action goes through the htmx same-origin link path (#258), a different
   * processor branch: the hoisted link still passes the check, so the form keeps its token.
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
    Compilation c = compile("String u", "method=\"post\" t:action=\"u\"");
    assertEquals(
        "<div><form method=\"post\" action=\"/x\">x</form></div>",
        c.render("form", Map.of("u", "/x"), ctx(new TestForms())));
  }
}
