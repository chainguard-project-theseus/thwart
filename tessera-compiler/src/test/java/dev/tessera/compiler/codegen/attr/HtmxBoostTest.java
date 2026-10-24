package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TrustedUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * htmx {@code hx-boost} (#258): htmx submits a boosted form with AJAX to its {@code action} (or the
 * submit button's {@code formaction}, also in a form with {@code hx-post} etc.) and swaps the
 * response into the page as HTML, so there a computed URL must be a same-origin link. A computed
 * {@code hx-boost} is a build error. Links are not affected: htmx boosts an {@code <a>} only when
 * its hostname is the page's.
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

  private static final String NOT_A_LINK = "inside hx-boost or an htmx form";

  /** A boosted form's computed action takes only a link: no String, concatenation or TrustedUrl. */
  @Test
  void boostedFormActionRejectsNonLinks() {
    for (String value : new String[] {"s", "'/x/' + s", "|/x/${s}|", "'/x'", "u", "@{/x} + s", "${s} ?: @{/x}"}) {
      compile("<section hx-boost=\"true\"><form t:action=\"" + value + "\"></form></section>")
          .assertError("t.html:1:", "t:action is not allowed", NOT_A_LINK, "@{/items/", "hx-boost=\"false\"");
    }
    compile("<section hx-boost=\"true\"><form t:attr=\"action=s\"></form></section>")
        .assertError("t.html:1:", "t:attr action is not allowed", NOT_A_LINK);
  }

  @Test
  void boostedFormActionRejectsOtherOrigins() {
    for (String path : new String[] {"https://evil.example/x", "//evil.example/x", "/\\evil.example/x", "edit", "~/x"}) {
      compile("<section hx-boost=\"true\"><form t:action=\"@{" + path + "}\"></form></section>")
          .assertError("t.html:1:", "t:action is not allowed", "same-origin path");
    }
    compile("<section hx-boost=\"true\"><form t:attr=\"action=@{//evil.example}\"></form></section>")
        .assertError("t:attr action is not allowed", "same-origin path");
  }

  /** Boost via data-hx-boost, on the form itself, far up through t:with / t:if, any case. */
  @Test
  void boostIsInheritedFromAnyStaticAncestor() {
    String[] bodies = {
      "<main data-hx-boost=\"true\"><form t:action=\"s\"></form></main>",
      "<form hx-boost=\"true\" t:action=\"s\"></form>",
      "<main HX-BOOST=\"true\"><ul><li t:with=\"x=${s}\"><div t:if=\"b\">"
          + "<form t:action=\"s\"></form></div></li></ul></main>",
      // htmx boosts only on exactly "true"; any other value but "false" is treated as boosted
      "<main hx-boost=\"TRUE\"><form t:action=\"s\"></form></main>",
      // opted out, then re-enabled below
      "<main hx-boost=\"true\"><div hx-boost=\"false\"><div hx-boost=\"true\"><form t:action=\"s\"></form></div></div></main>",
      // hx-disinherit is ignored: still checked
      "<main hx-boost=\"true\" hx-disinherit=\"*\"><form t:action=\"s\"></form></main>",
    };
    for (String body : bodies) {
      compile(body).assertError("t.html:1:", "t:action is not allowed", NOT_A_LINK);
    }
  }

  /** A fragment defined in a boosted subtree of the same template is checked in its method. */
  @Test
  void fragmentInsideBoostedSubtreeIsChecked() {
    compile("<main hx-boost=\"true\"><form t:fragment=\"f(String url)\" t:action=\"url\"></form></main>")
        .assertError("t.html:1:", "t:action is not allowed", NOT_A_LINK);
  }

  /** formaction is requested in a boosted subtree and in any form with an htmx request attribute. */
  @Test
  void formactionInHtmxFormsRejectsNonLinks() {
    String[] bodies = {
      "<main hx-boost=\"true\"><form><button t:formaction=\"s\">x</button></form></main>",
      "<main hx-boost=\"true\"><form><input type=\"submit\" t:formaction=\"s\"></form></main>",
      "<form hx-post=\"/save\"><button t:formaction=\"s\">x</button></form>",
      "<form data-hx-put=\"/save\"><p><button t:formaction=\"s\">x</button></p></form>",
      "<form t:hx-post=\"@{/save}\"><button t:formaction=\"s\">x</button></form>",
      "<form hx-get=\"/find\"><button t:attr=\"formaction=s\">x</button></form>",
    };
    for (String body : bodies) {
      compile(body).assertError("t.html:1:", "formaction is not allowed", NOT_A_LINK);
    }
    compile("<form hx-post=\"/save\"><button t:formaction=\"@{https://evil.example/}\">x</button></form>")
        .assertError("t.html:1:", "t:formaction is not allowed", "same-origin path");
  }

  /** A computed hx-boost, by any route, is a build error. */
  @Test
  void computedBoostIsABuildError() {
    for (String name : new String[] {"hx-boost", "data-hx-boost", "HX-Boost"}) {
      compile("<main t:" + name + "=\"b ? 'true' : 'false'\">x</main>")
          .assertError("t.html:1:", "t:" + name + " is not allowed", "cannot be computed", "statically");
      compile("<main t:attr=\"" + name + "=s\">x</main>")
          .assertError("t.html:1:", "t:attr " + name + " is not allowed", "cannot be computed");
    }
    // Vue/Alpine bindings of hx-boost are directives, not JSON bindings
    for (String name : new String[] {":hx-boost", "v-bind:hx-boost", "x-bind:data-hx-boost"}) {
      compile("<main t:" + name + "=\"b\">x</main>").assertError("t.html:1:", "is not allowed", "run as JavaScript");
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
    for (String name : new String[] {":formaction", "v-bind:formaction", "x-bind:formaction"}) {
      compile("<form hx-post=\"/x\"><button t:" + name + "=\"s\">x</button></form>")
          .assertError("t.html:1:", "is not allowed", "run as JavaScript");
    }
  }

  @Test
  void boostedSameOriginLinkIsEncoded() {
    Compilation c =
        compile("<main hx-boost=\"true\"><form t:action=\"@{/owners/{id}(id=${s})}\">"
                + "<button t:formaction=\"@{/x(q=${s})}\">x</button></form></main>")
            .assertSuccess();
    assertEquals(
        "<div><main hx-boost=\"true\"><form action=\"/owners/%2F%2Fevil.example%3F\">"
            + "<button formaction=\"/x?q=//evil.example%3F\">x</button></form></main></div>",
        render(c, "//evil.example?"));
  }

  /** hx-boost="false" on the form or between it and the boost: the ordinary URL attribute. */
  @Test
  void boostFalseOptsOut() {
    Compilation c =
        compile("<main hx-boost=\"true\"><form hx-boost=\"false\" t:action=\"s\"></form>"
                + "<div data-hx-boost=\"false\"><form t:action=\"s\"><button t:formaction=\"s\">x</button>"
                + "</form></div></main>")
            .assertSuccess();
    assertEquals(
        "<div><main hx-boost=\"true\"><form hx-boost=\"false\" action=\"about:invalid#tessera\"></form>"
            + "<div data-hx-boost=\"false\"><form action=\"about:invalid#tessera\">"
            + "<button formaction=\"about:invalid#tessera\">x</button></form></div></main></div>",
        render(c, "javascript:alert(1)"));
    assertEquals(
        "<div><main hx-boost=\"true\"><form hx-boost=\"false\" action=\"https://pay.example/x\"></form>"
            + "<div data-hx-boost=\"false\"><form action=\"https://pay.example/x\">"
            + "<button formaction=\"https://pay.example/x\">x</button></form></div></main></div>",
        render(c, "https://pay.example/x"));
  }

  /**
   * Unchanged: links in a boosted subtree (htmx boosts only same-hostname links), forms outside
   * one, a form with hx-post's own action, a boosted sibling, static values and static hx-boost.
   */
  @Test
  void outsideTheRuleIsUnchanged() {
    Compilation c =
        compile("<nav hx-boost=\"true\"><a t:href=\"s\">x</a><form action=\"https://pay.example/x\"></form></nav>"
                + "<form t:action=\"s\"><button t:formaction=\"s\">x</button></form>"
                + "<form hx-post=\"/save\" t:action=\"s\"></form>"
                + "<form hx-boost=\"false\"></form><div data-hx-boost=\"true\"></div>")
            .assertSuccess();
    assertEquals(
        "<div><nav hx-boost=\"true\"><a href=\"https://evil.example/x\">x</a>"
            + "<form action=\"https://pay.example/x\"></form></nav>"
            + "<form action=\"https://evil.example/x\"><button formaction=\"https://evil.example/x\">x</button></form>"
            + "<form hx-post=\"/save\" action=\"https://evil.example/x\"></form>"
            + "<form hx-boost=\"false\"></form><div data-hx-boost=\"true\"></div></div>",
        render(c, "https://evil.example/x"));
  }
}
