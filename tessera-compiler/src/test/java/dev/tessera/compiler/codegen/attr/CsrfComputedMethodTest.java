package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CSRF injection into forms whose method is computed (#209). */
class CsrfComputedMethodTest {
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

  private static String render(Compilation c, Map<String, Object> model, Forms forms) {
    return c.render("form", model, ctx(forms));
  }

  private static String render(String formAttrs) {
    return render(compile(null, formAttrs), Map.of(), withToken());
  }

  @Test
  void literalPostGetsToken() {
    assertEquals("<div><form method=\"post\">" + INPUT + "x</form></div>", render("t:method=\"'post'\""));
    assertEquals("<div><form method=\"POST\">" + INPUT + "x</form></div>", render("t:method=\"'POST'\""));
    assertEquals("<div><form method=\"PoSt\">" + INPUT + "x</form></div>", render("t:method=\"'PoSt'\""));
  }

  @Test
  void literalGetHasNoToken() {
    assertEquals("<div><form method=\"get\">x</form></div>", render("t:method=\"'get'\""));
    assertEquals("<div><form method=\"dialog\">x</form></div>", render("t:method=\"'dialog'\""));
  }

  @Test
  void attrMethodGetsToken() {
    assertEquals(
        "<div><form action=\"/a\" method=\"post\">" + INPUT + "x</form></div>",
        render("action=\"/a\" t:attr=\"method='post'\""));
  }

  @Test
  void computedMethodReplacesStaticOne() {
    assertEquals("<div><form method=\"post\">" + INPUT + "x</form></div>", render("method=\"get\" t:method=\"'post'\""));
    assertEquals("<div><form method=\"get\">x</form></div>", render("method=\"post\" t:method=\"'get'\""));
  }

  @Test
  void methodFromParamBothWays() {
    Compilation c = compile("String m", "action=\"/a\" t:method=\"m\"");
    Map<String, Object> model = new HashMap<>();
    model.put("m", "post");
    assertEquals("<div><form action=\"/a\" method=\"post\">" + INPUT + "x</form></div>", render(c, model, withToken()));
    model.put("m", "Post");
    assertEquals("<div><form action=\"/a\" method=\"Post\">" + INPUT + "x</form></div>", render(c, model, withToken()));
    model.put("m", "get");
    assertEquals("<div><form action=\"/a\" method=\"get\">x</form></div>", render(c, model, withToken()));
    model.put("m", null);
    assertEquals("<div><form action=\"/a\">x</form></div>", render(c, model, withToken()));
  }

  @Test
  void conditionalMethodFromParam() {
    Compilation c = compile("boolean edit", "t:method=\"edit ? 'post' : 'get'\"");
    assertEquals("<div><form method=\"post\">" + INPUT + "x</form></div>", render(c, Map.of("edit", true), withToken()));
    assertEquals("<div><form method=\"get\">x</form></div>", render(c, Map.of("edit", false), withToken()));
  }

  @Test
  void noTokenNoInput() {
    assertEquals(
        "<div><form method=\"post\">x</form></div>",
        render(compile(null, "t:method=\"'post'\""), Map.of(), new TestForms()));
  }

  @Test
  void methodInsideLoopEvaluatedPerIteration() {
    Compilation c =
        Compilation.builder()
            .template(
                "form.html",
                "<div xmlns:t=\"https://tessera.dev\" t:params=\"java.util.List<String> ms\">"
                    + "<form t:each=\"m : ms\" t:method=\"m\">x</form></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><form method=\"post\">" + INPUT + "x</form><form method=\"get\">x</form></div>",
        render(c, Map.of("ms", java.util.List.of("post", "get")), withToken()));
  }

  /** Negative: the computed-POST opt-out from #209 no longer yields a token-less POST form. */
  @Test
  void computedPostCannotDropToken() {
    String out = render("t:method=\"'post'\" t:action=\"'/save'\"");
    assertTrue(out.contains(INPUT), out);
    assertEquals("<div><form method=\"post\" action=\"/save\">" + INPUT + "x</form></div>", out);
  }

  /**
   * Negative: a value a browser does not read as POST (it submits as GET) never gets the token, so
   * it cannot leak into a URL. Unicode case folding ({@code ſ} uppercases to {@code S}) and
   * surrounding whitespace do not count.
   */
  @Test
  void nonPostLookalikesGetNoToken() {
    Compilation c = compile("String m", "t:method=\"m\"");
    for (String m : new String[] {"poſt", "POſT", " post", "post ", "posts", "", "pöst"}) {
      Map<String, Object> model = new HashMap<>();
      model.put("m", m);
      String out = render(c, model, withToken());
      assertFalse(out.contains("_csrf"), m + ": " + out);
    }
  }

  /**
   * Negative: a static method is read the same way. {@code method=" post"} submits as GET in a
   * browser, so it gets no token; entity-encoded {@code post} is still POST.
   */
  @Test
  void staticMethodUsesBrowserRule() {
    for (String m : new String[] {" post", "post ", "\tpost", "poſt"}) {
      String out = render("method=\"" + m + "\"");
      assertFalse(out.contains("_csrf"), m + ": " + out);
    }
    assertTrue(render("method=\"&#112;OST\"").contains(INPUT));
    assertTrue(render("method=\"Post\"").contains(INPUT));
  }
}
