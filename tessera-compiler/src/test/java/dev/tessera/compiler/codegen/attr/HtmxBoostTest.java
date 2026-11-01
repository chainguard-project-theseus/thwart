package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TrustedUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code hx-boost} changes no rule (#583, #585): what htmx does with a boosted form is outside the
 * guarantee, so a form's computed {@code action} follows the ordinary URL rules whether or not
 * anything boosts it, and a POST form keeps the CSRF closed shape ({@link CsrfInjector}), which is
 * about HTML. A computed {@code hx-boost} stays a build error until #584.
 */
class HtmxBoostTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s, TrustedUrl u, boolean b\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(Compilation c, String s) {
    return c.render("t", Map.of("s", s, "u", TrustedUrl.unchecked("https://evil.example/"), "b", true));
  }

  /** The boosted spellings the old check (#258) rejected: each is now the ordinary URL attribute. */
  private static final String[] BOOSTED = {
    "<main data-hx-boost=\"true\"><form t:action=\"s\"></form></main>",
    "<form hx-boost=\"true\" t:action=\"s\"></form>",
    "<main HX-BOOST=\"true\"><ul><li t:with=\"x=${s}\"><div t:if=\"b\"><form t:action=\"s\"></form></div></li></ul></main>",
    "<main hx-boost=\"true\"><div hx-boost=\"false\"><div hx-boost=\"true\"><form t:action=\"s\"></form></div></div></main>",
    "<main hx-boost=\"true\"><t:block hx-boost=\"false\"><form t:action=\"s\"></form></t:block></main>",
    "<main hx-boost=\"true\"><form t:attr=\"action=s\"></form></main>",
  };

  /** Positive: a boosted GET form's computed action is a URL attribute like any other. */
  @Test
  void boostedFormActionIsAnOrdinaryUrl() {
    for (String body : BOOSTED) {
      String out = render(compile(body).assertSuccess(), "https://pay.example/x");
      assertEquals(true, out.contains("action=\"https://pay.example/x\""), out);
    }
    String out = render(compile("<main hx-boost=\"true\"><form t:action=\"u\"></form></main>").assertSuccess(), "");
    assertEquals("<div><main hx-boost=\"true\"><form action=\"https://evil.example/\"></form></main></div>", out);
    compile("<main hx-boost=\"true\"><form t:fragment=\"f(String url)\" t:action=\"url\"></form></main>").assertSuccess();
  }

  /** Negative: the ordinary URL rules still apply under a boost; a script URL is neutralized. */
  @Test
  void boostedFormActionKeepsTheSchemeCheck() {
    for (String body : BOOSTED) {
      String out = render(compile(body).assertSuccess(), "javascript:alert(1)");
      assertEquals(true, out.contains("action=\"about:invalid#tessera\""), out);
      assertEquals(false, out.contains("javascript:"), out);
    }
  }

  /** Negative: a boosted POST form keeps the closed shape: a computed action is a same-origin link. */
  @Test
  void boostedPostFormKeepsTheClosedShape() {
    compile("<main hx-boost=\"true\"><form method=\"post\" t:action=\"s\"></form></main>")
        .assertError("t.html:1:", "t:action is not allowed", "the CSRF token");
    compile("<main hx-boost=\"true\"><form method=\"post\" t:action=\"@{//evil.example/x}\"></form></main>")
        .assertError("t.html:1:", "t:action is not allowed", "same-origin path");
  }

  /** Negative: every formaction takes only a same-origin link (#314), whatever htmx attributes are around. */
  @Test
  void formactionIsCheckedEverywhere() {
    String[] bodies = {
      "<main hx-boost=\"true\"><form><button t:formaction=\"s\">x</button></form></main>",
      "<main hx-boost=\"true\"><form><input type=\"submit\" t:formaction=\"s\"></form></main>",
      "<form hx-post=\"/save\"><button t:formaction=\"s\">x</button></form>",
      "<form hx-get=\"/find\"><button t:attr=\"formaction=s\">x</button></form>",
      "<form><button t:formaction=\"s\">x</button></form>",
    };
    for (String body : bodies) {
      compile(body).assertError("t.html:1:", "formaction is not allowed", "to its formaction");
    }
  }

  /** A computed hx-boost, by any route, is a build error (attribute modelling, until #584). */
  @Test
  void computedBoostIsABuildError() {
    for (String name : new String[] {"hx-boost", "data-hx-boost", "HX-Boost"}) {
      compile("<main t:" + name + "=\"b ? 'true' : 'false'\">x</main>")
          .assertError("t.html:1:", "t:" + name + " is not allowed", "cannot be computed", "statically");
      compile("<main t:attr=\"" + name + "=s\">x</main>")
          .assertError("t.html:1:", "t:attr " + name + " is not allowed", "cannot be computed");
    }
  }

  /** Vue/Alpine bindings of action/formaction are directives (#246 path), boosted or not. */
  @Test
  void bindingsOfActionAreDirectives() {
    for (String name : new String[] {":action", "v-bind:action", "x-bind:action"}) {
      compile("<main hx-boost=\"true\"><form t:" + name + "=\"s\"></form></main>")
          .assertError("t.html:1:", "is not allowed", "run as JavaScript");
      compile("<form t:" + name + "=\"s\"></form>").assertError("t.html:1:", "run as JavaScript");
    }
  }

  /** Positive: links and static values in a boosted subtree render as written. */
  @Test
  void boostedSubtreeRendersAsWritten() {
    Compilation c =
        compile("<nav hx-boost=\"true\"><a t:href=\"s\">x</a><form action=\"https://pay.example/x\"></form>"
                + "<form t:action=\"@{/owners/{id}(id=${s})}\"><button t:formaction=\"@{/x(q=${s})}\">x</button></form></nav>")
            .assertSuccess();
    assertEquals(
        "<div><nav hx-boost=\"true\"><a href=\"//evil.example?\">x</a><form action=\"https://pay.example/x\"></form>"
            + "<form action=\"/owners/%2F%2Fevil.example%3F\"><button formaction=\"/x?q=//evil.example%3F\">x</button>"
            + "</form></nav></div>",
        render(c, "//evil.example?"));
  }
}
