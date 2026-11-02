package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * A submit button sends its form, CSRF token included, to its {@code formaction} (#314). Where the
 * form may get the token, and a form attribute or a fragment can make a button submit any form, every
 * computed formaction must be a same-origin link, every static one same-origin by shape, and every
 * formmethod a static POST or dialog, in any form or none (closed shape).
 */
class CsrfFormactionTest {
  private static final String INPUT = "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />";
  private static final String EVIL = "https://evil.example/collect";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u, String m, int id\">\n"
            + body + "\n</div>")
        .compile();
  }

  private static String render(Compilation c) {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    return c.render("page", Map.of("u", EVIL, "m", "get", "id", 7), ctx(forms));
  }

  private static void rejected(String body, String... message) {
    Compilation c = compile(body);
    c.assertError("page.html:2:");
    c.assertError(message);
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  private static final String POST = "<form method=\"post\" action=\"/transfer\">";

  /** Negative: each way to compute or write a cross-origin formaction in a token-bearing form. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<button t:formaction=\"${u}\">Go</button>",
        "<button t:formaction=\"u\">Go</button>",
        "<button t:title=\"${u}\" t:FormAction=\"${u}\">Go</button>",
        "<button t:FORMACTION=\"${u}\">Go</button>",
        "<button t:formaction=\"@{//evil.example/x}\">Go</button>",
        "<button t:formaction=\"@{/\\evil.example/x}\">Go</button>",
        "<button t:formaction=\"@{https://evil.example/x}\">Go</button>",
        "<input type=\"submit\" t:formaction=\"${u}\">",
        "<input type=\"image\" src=\"/i.png\" t:formaction=\"${u}\">",
        "<button formaction=\"https://evil.example/x\">Go</button>",
        "<button FORMACTION=\"//evil.example/x\">Go</button>",
        "<button formaction=\"&sol;&sol;evil.example/x\">Go</button>",
        "<input type=\"submit\" formaction=\"/\\evil.example/x\">",
      })
  void crossOriginFormactionInTokenFormFails(String button) {
    rejected(POST + button + "</form>", "formaction");
  }

  @Test
  void errorNamesFileLineColumnAndFix() {
    rejected(
        POST + "<button t:formaction=\"${u}\">Go</button></form>",
        "page.html:2:48:",
        "t:formaction is not allowed: a submit button sends its form, CSRF token included, to its formaction;"
            + " use a same-origin link such as @{/items/{id}(id=${item.id})}");
    rejected(
        POST + "<button t:formaction=\"@{//evil.example/x}\">Go</button></form>",
        "must be a same-origin path starting with a single '/', not @{//evil.example/x}");
    rejected(POST + "<button formaction=\"https://evil.example/x\">Go</button></form>", "page.html:2:48:", "not same-origin");
  }

  /** Negative: a form whose method or action is computed may get the token. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form t:method=\"${m}\"><button t:formaction=\"${u}\">Go</button></form>",
        "<form method=\"post\" t:action=\"${u}\"><button t:formaction=\"${u}\">Go</button></form>",
        "<form method=\"POST\"><button t:formaction=\"${u}\">Go</button></form>",
      })
  void formThatMayGetTokenFails(String body) {
    rejected(body, "t:formaction");
  }

  /** Negative: a button with a form attribute submits that form, wherever it is. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form id=\"f\" method=\"post\"></form><button form=\"f\" t:formaction=\"${u}\">Go</button>",
        "<form id=\"f\" method=\"post\"></form><form><button form=\"f\" t:formaction=\"${u}\">Go</button></form>",
        "<form method=\"get\"><button t:form=\"'f'\" t:formaction=\"${u}\">Go</button></form>",
        "<form method=\"get\"><button form=\"f\" formaction=\"https://evil.example/x\">Go</button></form>",
      })
  void formAttributeFails(String body) {
    rejected(body, "formaction");
  }

  /**
   * Negative: a browser drops a nested form's start tag, so the button submits the outer POST
   * form; a fragment or {@code ~{::button}} selection may be rendered into a POST form elsewhere.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        POST + "<form method=\"get\"><button t:formaction=\"${u}\">Go</button></form></form>",
        "<form method=\"get\">" + POST + "<button t:formaction=\"${u}\">Go</button></form></form>",
        "<button t:formaction=\"${u}\">Go</button>",
        "<form method=\"get\"><button t:fragment=\"go(String u)\" t:formaction=\"${u}\">Go</button></form>",
        "<form><div t:fragment=\"bar\"><button formaction=\"https://evil.example/x\">Go</button></div></form>",
        "<form method=\"get\"><button t:formaction=\"${u}\">Go</button></form>"
            + "<div t:replace=\"~{:: wrap(~{::button})}\"></div>"
            + "<div t:fragment=\"wrap(Fragment b)\">" + POST + "<div t:replace=\"${b}\"></div></form></div>",
        "<form method=\"get\" t:remove=\"tag\"><button t:formaction=\"${u}\">Go</button></form>",
        // A GET form selected or in a fragment may be inserted into a POST form, which drops its start tag.
        "<form method=\"get\"><button t:formaction=\"${u}\">Go</button></form>"
            + "<div t:replace=\"~{:: wrap(~{::form})}\"></div>"
            + "<div t:fragment=\"wrap(Fragment b)\">" + POST + "<div t:replace=\"${b}\"></div></form></div>",
        "<form t:fragment=\"get(String u)\" method=\"get\"><button t:formaction=\"${u}\">Go</button></form>",
        "<div t:fragment=\"pay(String u)\"><form method=\"post\" action=\"https://pay.example/\">"
            + "<button t:formaction=\"${u}\">Go</button></form></div>",
      })
  void nestedFormsAndFragmentsFail(String body) {
    rejected(body, "formaction");
  }

  /** Negative: a formmethod other than post submits as GET with the token in the URL. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<button formmethod=\"get\">Go</button>",
        "<button formmethod=\"GET\" formaction=\"/search\">Go</button>",
        "<button formmethod=\" post\">Go</button>",
        "<input type=\"submit\" formmethod=\"\">",
        "<button t:formmethod=\"${m}\">Go</button>",
        "<button t:formmethod=\"'post'\">Go</button>",
      })
  void formmethodInTokenFormFails(String button) {
    rejected(POST + button + "</form>", "formmethod", "with its CSRF token in the URL");
  }

  /** Negative: htmx sends the enclosing form's inputs, token included; its URL must be a same-origin link. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<button t:hx-post=\"${u}\">Go</button>",
        "<button t:hx-post=\"@{//evil.example/x}\">Go</button>",
        "<button hx-post=\"/x\" t:formaction=\"${u}\">Go</button>",
      })
  void htmxOnButtonFails(String button) {
    rejected(POST + button + "</form>", "is not allowed");
  }

  /** Negative: a GET form from another file inserted into a POST form has its start tag dropped. */
  @Test
  void getFormFragmentFromAnotherFileFails() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u\">\n"
                + POST + "<div t:replace=\"~{frag :: g(${u})}\"></div></form>\n</div>")
            .template("frag.html", "<div xmlns:t=\"https://tessera.dev\">\n"
                + "<form t:fragment=\"g(String u)\" method=\"get\"><button t:formaction=\"${u}\">Go</button></form></div>")
            .compile();
    c.assertError("frag.html:2:", "t:formaction is not allowed", "sends its form, CSRF token included");
  }

  /**
   * Negative: a browser treats {@code <form/>} as open, so later forms' start tags are dropped and
   * their buttons (and token inputs) land in it.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form method=\"post\" action=\"/t\"/><form method=\"get\"><button t:formaction=\"${u}\">Go</button></form>",
        "<form method=\"get\" action=\"/s\"/>" + POST + "<button>Go</button></form>",
      })
  void selfClosingFormFails(String body) {
    rejected(body, "<form/> is not self-closing in HTML");
  }

  /** Without its tag a form gets no token input: it would land in the enclosing (GET) form. */
  @Test
  void formWithoutTagGetsNoToken() {
    String out =
        render(compile("<form method=\"get\"><form method=\"post\" action=\"/t\" t:remove=\"tag\">"
            + "<button>Go</button></form></form>").assertSuccess());
    assertFalse(out.contains("_csrf"), out);
  }

  /** Positive: a same-origin link or static URL keeps the token. */
  @Test
  void sameOriginFormactionKeepsToken() {
    Compilation c =
        compile(POST + "<button t:formaction=\"@{/items/{id}/delete(id=${id})}\">Del</button>"
            + "<button formmethod=\"post\" t:formaction=\"@{/items}\">A</button>"
            + "<input type=\"submit\" formaction=\"/items/new\" formmethod=\"dialog\">"
            + "<button formaction=\"edit?x=1\" FORMMETHOD=\"Post\">B</button></form>")
            .assertSuccess();
    assertEquals(
        "<div>\n" + POST + INPUT + "<button formaction=\"/items/7/delete\">Del</button>"
            + "<button formmethod=\"post\" formaction=\"/items\">A</button>"
            + "<input type=\"submit\" formaction=\"/items/new\" formmethod=\"dialog\">"
            + "<button formaction=\"edit?x=1\" FORMMETHOD=\"Post\">B</button></form>\n</div>",
        render(c));
  }

  private static Compilation compileChoice(String button) {
    return Compilation.builder()
        .template("page.html", "<div xmlns:t=\"https://tessera.dev\""
            + " t:params=\"boolean b, boolean c, String u, int id\">\n" + POST + button + "</form>\n</div>")
        .compile();
  }

  /** Positive (#635): a choice whose every branch is a same-origin link, also nested, keeps the token. */
  @Test
  void choiceOfSameOriginLinksKeepsToken() {
    Compilation c = compileChoice(
        "<button t:formaction=\"b ? @{/items/{id}/delete(id=${id})} : @{/items(page='')}\">A</button>"
            + "<button t:formaction=\"${b} ? @{/x} : (c ? @{/y} : @{/z})\">B</button>")
        .assertSuccess();
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    for (Object[] row : new Object[][] {
        {true, false, "/items/7/delete", "/x"}, {false, true, "/items?page=", "/y"}, {false, false, "/items?page=", "/z"}}) {
      String out = c.render("page", Map.of("b", row[0], "c", row[1], "u", EVIL, "id", 7), ctx(forms));
      assertEquals("<div>\n" + POST + INPUT + "<button formaction=\"" + row[2] + "\">A</button>"
          + "<button formaction=\"" + row[3] + "\">B</button></form>\n</div>", out);
    }
  }

  /** Negative (#635): a choice with any branch that is not a same-origin link stays a build error. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<button t:formaction=\"b ? ${u} : @{/a}\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : ${u}\">Go</button>",
        "<button t:formaction=\"b ? ${u}\">Go</button>",
        "<button t:formaction=\"${b} ? @{/a} : ${u}\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : '/b'\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : '/b/' + ${u}\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : @{//evil.example/x}\">Go</button>",
        "<button t:formaction=\"b ? @{https://evil.example/x} : @{/a}\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : @{x}\">Go</button>",
        "<button t:formaction=\"b ? @{/a} : (c ? @{/b} : ${u})\">Go</button>",
        "<button t:formaction=\"${u} ?: @{/a}\">Go</button>",
        "<input type=\"submit\" t:formaction=\"b ? @{/a} : ${u}\">",
      })
  void choiceWithAnyUnprovenBranchFails(String button) {
    Compilation c = compileChoice(button);
    c.assertError("page.html:2:", "formaction", "is not allowed");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  /** Negative (#635): a branch with a computed link path is a parse error, as a lone one is. */
  @Test
  void choiceWithComputedLinkPathFails() {
    Compilation c = compileChoice("<button t:formaction=\"b ? @{/a} : @{${u}}\">Go</button>");
    c.assertError("page.html:2:", "dynamic link paths");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  /**
   * Negative: formaction and formmethod are checked in every form, also GET forms and forms posting
   * to another site, so no rule depends on which form owns the button.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form method=\"get\"><button t:formaction=\"${u}\">A</button></form>",
        "<form method=\"get\"><button t:formmethod=\"${m}\">A</button></form>",
        "<form><button formaction=\"https://evil.example/x\">B</button></form>",
        "<form><button formmethod=\"get\">B</button></form>",
        "<form method=\"post\" action=\"https://pay.example/\"><button t:formaction=\"${u}\">C</button></form>",
        "<form method=\"get\"><div><input type=\"submit\" t:formaction=\"${u}\"></div></form>",
        "<button formaction=\"//evil.example/x\">D</button>",
      })
  void everyFormIsChecked(String body) {
    rejected(body, "is not allowed");
  }

  /** A nested {@code <form>} is a parse error (a browser drops it): a build error since #406. */
  @Test
  void nestedFormFails() {
    compile("<form method=\"get\"><div><form method=\"post\"><button>x</button></form></div></form>")
        .assertError("page.html:2:", "Nested forms are not allowed", "[verifier:PARSE_ERROR]");
  }
}
