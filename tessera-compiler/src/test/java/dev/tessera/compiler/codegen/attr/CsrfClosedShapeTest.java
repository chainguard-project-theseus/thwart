package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The scenarios of the earlier CSRF bugs, each either tokenless by construction or a build error
 * under the closed shape ({@link CsrfInjector}).
 */
class CsrfClosedShapeTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String FRAG = "t:import=\"dev.tessera.runtime.Fragment\"";

  private static Compilation page(String body) {
    return Compilation.builder().template("page.html", "<div " + NS + " t:params=\"String u\">" + body + "</div>").compile();
  }

  private static String render(Compilation c) {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    return c.render("page", Map.of("u", "https://evil.example/"), ctx(forms));
  }

  /** #285: an action from the model. */
  @Test
  void modelAction() {
    page("<form method=\"post\" t:action=\"u\"><button>Go</button></form>")
        .assertError("page.html:1:", "t:action is not allowed", "the CSRF token");
  }

  /** #386: a converted (toString) action or method. */
  @Test
  void convertedActionOrMethod() {
    page("<form method=\"post\" t:action=\"${u}\"></form>").assertError("t:action is not allowed");
    page("<form t:method=\"u\" action=\"/x\"></form>").assertError("t:method is not allowed", "must be static");
  }

  /** #387: a literal absolute link to the application's own host. */
  @Test
  void absoluteLinkAction() {
    page("<form method=\"post\" t:action=\"@{https://www.example.com/owners}\"></form>")
        .assertError("must be a same-origin path starting with a single '/'");
  }

  /** #314: formaction, in any form, also a GET form a fragment may put into a POST one. */
  @Test
  void formaction() {
    page("<form method=\"post\"><button formaction=\"https://evil.example/\">Go</button></form>")
        .assertError("formaction=");
    page("<form method=\"get\"><button t:formaction=\"u\">Go</button></form>").assertError("t:formaction is not allowed");
    page("<form method=\"post\"><button formmethod=\"get\">Go</button></form>").assertError("formmethod=");
  }

  /** A button outside its form ({@code form="id"}). */
  @Test
  void formAttributeButton() {
    page("<form id=\"f\" method=\"post\"></form><button form=\"f\" formaction=\"//evil.example/\">Go</button>")
        .assertError("formaction=");
  }

  /**
   * #583, #585: htmx is not modelled. What htmx sends where ({@code hx-post}, {@code hx-ws}, a
   * boost) is outside the guarantee (use htmx 2 or {@code selfRequestsOnly}); the closed shape is
   * about what the browser submits natively, so the form gets its token because its own action is
   * same-origin, and the htmx attributes render as written.
   */
  @Test
  void htmxIsNotModelled() {
    Compilation c = page("<form method=\"post\" hx-post=\"https://evil.example/\"><button>Go</button></form>"
        + "<form method=\"post\" action=\"/x\" hx-ws=\"connect:wss://evil.example/\">"
        + "<button data-hx-put=\"//evil.example/\" hx-sse=\"connect:/e\">Go</button></form>").assertSuccess();
    assertEquals("<div><form method=\"post\" hx-post=\"https://evil.example/\">"
        + "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" /><button>Go</button></form>"
        + "<form method=\"post\" action=\"/x\" hx-ws=\"connect:wss://evil.example/\">"
        + "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />"
        + "<button data-hx-put=\"//evil.example/\" hx-sse=\"connect:/e\">Go</button></form></div>", render(c));
  }

  /** #350: a cross-origin {@code <base>} anywhere, a layout's included. */
  @Test
  void base() {
    page("<base href=\"https://evil.example/\"><form method=\"post\"></form>").assertError("on <base> is not allowed");
    page("<base href=\"//evil.example/\">").assertError("on <base> is not allowed");
    page("<base href=\"&sol;&sol;evil.example/\">").assertError("on <base> is not allowed");
    page("<base t:href=\"u\">").assertError("t:href is not allowed");
  }

  /**
   * #498: a boost from a layout needs no tracking. A POST form's computed action is a same-origin
   * link or an error, and a GET form's a same-origin link or a TrustedUrl (#585), boosted or not.
   */
  @Test
  void boostFromLayout() {
    String layout = "<div " + NS + " " + FRAG + "><main t:fragment=\"l(Fragment c)\" hx-boost=\"true\">"
        + "<div t:replace=\"${c}\"></div></main></div>";
    Compilation.builder()
        .template("layout.html", layout)
        .template("page.html", "<div " + NS + " t:params=\"String u\" t:replace=\"~{layout :: l(~{::section})}\">"
            + "<section><form method=\"post\" t:action=\"u\"><button>Go</button></form></section></div>")
        .compile()
        .assertError("page.html:1:", "t:action is not allowed", "the CSRF token");
    Compilation.builder()
        .template("layout.html", layout)
        .template("page.html", "<div " + NS + " t:params=\"String u\" t:replace=\"~{layout :: l(~{::section})}\">"
            + "<section><form t:action=\"u\"><button>Go</button></form></section></div>")
        .compile()
        .assertError("page.html:1:", "t:action is not allowed", "must stay on the page's origin");
    Compilation c = Compilation.builder()
        .template("layout.html", layout)
        .template("page.html", "<div " + NS + " t:replace=\"~{layout :: l(~{::section})}\">"
            + "<section><form t:action=\"@{/s}\"><button>Go</button></form></section></div>")
        .compile()
        .assertSuccess();
    assertEquals("<main hx-boost=\"true\"><section><form action=\"/s\"><button>Go</button></form>"
        + "</section></main>", render(c));
  }

  /** A form nested in another, directly or through a fragment or layout, is a verifier parse error. */
  @Test
  void nestedForms() {
    page("<form method=\"get\"><div><form method=\"post\"></form></div></form>").assertError("[verifier:PARSE_ERROR]");
    Compilation.builder()
        .template("frag.html", "<div " + NS + "><form t:fragment=\"f\" method=\"post\" action=\"/x\"></form></div>")
        .template("page.html", "<div " + NS + "><form method=\"get\" action=\"/s\"><div t:insert=\"~{frag :: f}\"></div></form></div>")
        .compile()
        .assertError("Nested forms are not allowed", "[verifier:PARSE_ERROR]");
    Compilation.builder()
        .template("layout.html", "<div " + NS + " " + FRAG + "><div t:fragment=\"wrap(Fragment c)\">"
            + "<form method=\"get\" action=\"/s\"><div t:replace=\"${c}\"></div></form></div></div>")
        .template("page.html", "<div " + NS + " t:replace=\"~{layout :: wrap(~{::section})}\">"
            + "<section><form method=\"post\" action=\"/x\"></form></section></div>")
        .compile()
        .assertError("Nested forms are not allowed", "[verifier:PARSE_ERROR]");
  }

  /** In the shape: the token; a static cross-origin action: none, by construction. */
  @Test
  void inShape() {
    Compilation c = page("<form method=\"post\" t:action=\"@{/x}\"><button formaction=\"/y\">Go</button></form>"
        + "<form method=\"post\" action=\"https://pay.example/\"></form>").assertSuccess();
    assertEquals("<div><form method=\"post\" action=\"/x\"><input type=\"hidden\" name=\"_csrf\" value=\"tok\" />"
        + "<button formaction=\"/y\">Go</button></form><form method=\"post\" action=\"https://pay.example/\"></form></div>",
        render(c));
    assertFalse(render(page("<form method=\"get\" t:action=\"@{/s}\"></form>").assertSuccess()).contains("_csrf"));
  }
}
