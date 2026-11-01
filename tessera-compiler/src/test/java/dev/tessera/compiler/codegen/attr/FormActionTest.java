package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TrustedUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A form's computed {@code action} (#585, replacing the {@code hx-boost} tracking of #258): client
 * code such as htmx 1.x may submit any form with AJAX and swap the response into the page as HTML,
 * so a computed action is a same-origin {@code @{}} link or a {@code TrustedUrl}, with or without
 * htmx, and a static POST form keeps the stricter CSRF closed shape ({@link CsrfInjector}). Static
 * actions are unchanged. A computed {@code hx-boost} stays a build error until #584.
 */
class FormActionTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s, TrustedUrl u, boolean b\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(Compilation c, String s) {
    return c.render("t", Map.of("s", s, "u", TrustedUrl.unchecked("https://pay.example/x"), "b", true));
  }

  private static final String NOT_A_LINK = "a form's computed action must stay on the page's origin";

  /** Negative: a String, a concatenation or a choice is a build error, boosted or not, by any route. */
  @Test
  void stringActionIsABuildError() {
    String[] bodies = {
      "<form t:action=\"${s}\"></form>",
      "<form t:action=\"s\"></form>",
      "<form method=\"get\" t:action=\"'/x/' + s\"></form>",
      "<form t:action=\"|/x/${s}|\"></form>",
      "<form t:action=\"'/x'\"></form>",
      "<form t:action=\"@{/x} + s\"></form>",
      "<form t:action=\"${s} ?: @{/x}\"></form>",
      "<form t:fragment=\"f(String url)\" t:action=\"url\"></form>",
      "<main hx-boost=\"true\"><form t:action=\"s\"></form></main>",
      "<main data-hx-boost=\"true\"><div hx-boost=\"false\"><form t:action=\"s\"></form></div></main>",
      "<form hx-boost=\"false\" t:action=\"s\"></form>",
    };
    for (String body : bodies) {
      compile(body).assertError("t.html:1:", "t:action is not allowed", NOT_A_LINK, "@{/items/", "TrustedUrl");
    }
    compile("<form t:attr=\"action=s\"></form>").assertError("t.html:1:", "t:attr action is not allowed", NOT_A_LINK);
  }

  /** Negative: a link that leaves the origin is a build error. */
  @Test
  void otherOriginLinkIsABuildError() {
    for (String path : new String[] {"https://evil.example/x", "//evil.example/x", "/\\evil.example/x", "edit", "~/x"}) {
      compile("<form t:action=\"@{" + path + "}\"></form>")
          .assertError("t.html:1:", "t:action is not allowed", "the action of a form must be a same-origin path");
    }
    compile("<form t:attr=\"action=@{//evil.example}\"></form>").assertError("t:attr action is not allowed", "same-origin path");
  }

  /** Negative: a static POST form keeps the closed shape: not even a TrustedUrl (#285). */
  @Test
  void postFormKeepsTheClosedShape() {
    compile("<form method=\"post\" t:action=\"u\"></form>").assertError("t.html:1:", "t:action is not allowed", "the CSRF token");
    compile("<main hx-boost=\"true\"><form method=\"post\" t:action=\"s\"></form></main>")
        .assertError("t.html:1:", "t:action is not allowed", "the CSRF token");
  }

  /** Positive: a same-origin link, encoded and re-checked when rendered. */
  @Test
  void sameOriginLinkCompiles() {
    Compilation c =
        compile("<form t:action=\"@{/owners/{id}(id=${s})}\"></form><form t:attr=\"action=@{/find(q=${s})}\"></form>")
            .assertSuccess();
    assertEquals("<div><form action=\"/owners/%2F%2Fevil.example%3F\"></form>"
        + "<form action=\"/find?q=//evil.example%3F\"></form></div>", render(c, "//evil.example?"));
  }

  /** Positive: a TrustedUrl built in Java goes as is, also to another site and under a boost. */
  @Test
  void trustedUrlCompiles() {
    Compilation c = compile("<form t:action=\"u\"></form><main hx-boost=\"true\"><form t:attr=\"action=u\"></form></main>")
        .assertSuccess();
    assertEquals("<div><form action=\"https://pay.example/x\"></form>"
        + "<main hx-boost=\"true\"><form action=\"https://pay.example/x\"></form></main></div>", render(c, ""));
  }

  /** Static actions are unchanged: written as is, boosted or not. */
  @Test
  void staticActionIsUnchanged() {
    Compilation c = compile("<main hx-boost=\"true\"><form action=\"https://pay.example/x\"></form></main>").assertSuccess();
    assertEquals("<div><main hx-boost=\"true\"><form action=\"https://pay.example/x\"></form></main></div>", render(c, ""));
  }

  /** Negative: every formaction takes only a same-origin link (#314), with or without htmx. */
  @Test
  void formactionIsCheckedEverywhere() {
    String[] bodies = {
      "<form><button t:formaction=\"s\">x</button></form>",
      "<form><button t:formaction=\"u\">x</button></form>",
      "<main hx-boost=\"true\"><form><input type=\"submit\" t:formaction=\"s\"></form></main>",
      "<form hx-get=\"/find\"><button t:attr=\"formaction=s\">x</button></form>",
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

  /** Vue/Alpine bindings of action/formaction are directives (#246 path). */
  @Test
  void bindingsOfActionAreDirectives() {
    for (String name : new String[] {":action", "v-bind:action", "x-bind:action", ":formaction"}) {
      compile("<form t:" + name + "=\"s\"></form>").assertError("t.html:1:", "is not allowed", "run as JavaScript");
    }
  }
}
