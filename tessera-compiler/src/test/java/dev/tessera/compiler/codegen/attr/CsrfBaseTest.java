package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.TrustedUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every relative URL on a page resolves against its {@code <base href>}, so a cross-origin base
 * sends a POST form with a relative action, CSRF token included, to another site (#350). A
 * {@code <base href>} must be same-origin, in every template: a layout's {@code <base>} applies to
 * forms that fragments from other templates insert.
 */
class CsrfBaseTest {
  private static final String INPUT = "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />";
  private static final String FORM = "<form method=\"post\" action=\"/transfer\"><button formaction=\"ok\">Go</button></form>";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u, TrustedUrl t\">\n"
            + body + "\n</div>")
        .compile();
  }

  private static void rejected(String body, String... message) {
    Compilation c = compile(body);
    c.assertError("page.html:2:");
    c.assertError(message);
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }

  /** Negative: the issue's case; the POST (and the button's) would go to evil.example with the token. */
  @Test
  void crossOriginBaseWithTokenFormFails() {
    rejected(
        "<base href=\"https://evil.example/\">" + FORM,
        "page.html:2:7:",
        "href=\"https://evil.example/\" on <base> is not allowed",
        "every relative URL on the page resolves against <base href>",
        "use a same-origin path such as /app/, or remove <base> and write absolute URLs");
  }

  /** Negative: each way to write a static base on another origin, with or without a form in the file. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<base href=\"https://evil.example/\">",
        "<base href=\"//evil.example/\">",
        "<base href=\"/\\evil.example/\">",
        "<base href=\"\\\\evil.example/\">",
        "<base href=\" //evil.example/\">",
        "<base href=\"/&#9;/evil.example/\">",
        "<base href=\"&sol;&sol;evil.example/\">",
        "<base href=\"https&colon;//evil.example/\">",
        "<base HREF=\"https://evil.example/\">",
        "<base href=\"http:evil.example/\">",
        "<base href=\"javascript:alert(1)//\">",
        "<base target=\"_top\" href=\"//evil.example/\" />",
        "<base href=\"https://evil.example/\" href=\"/\">",
        "<base href=\"https://evil.example/\" t:if=\"${u != null}\">",
      })
  void crossOriginStaticBaseFails(String base) {
    rejected(base, "on <base> is not allowed");
    rejected(base + FORM, "on <base> is not allowed");
  }

  /** Negative: a computed base must be a same-origin link; a String or TrustedUrl is not enough. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<base t:href=\"@{//evil.example/}\">",
        "<base t:href=\"@{https://evil.example/}\">",
        "<base t:href=\"@{/\\evil.example/}\">",
        "<base t:href=\"${u}\">",
        "<base t:href=\"${t}\">",
        "<base t:HREF=\"${t}\">",
        "<base t:attr=\"href=${t}\">",
        "<base t:attr=\"target=${u}, href=@{//evil.example/}\">",
        "<base t:href=\"${u} != null ? @{/a/} : @{//evil.example/}\">",
      })
  void computedBaseNotSameOriginLinkFails(String base) {
    rejected(base + FORM, "is not allowed");
  }

  /** Negative: the base in a layout, the POST form in a fragment from another file. */
  @Test
  void layoutBaseFailsWhateverTheFragments() {
    Compilation c =
        Compilation.builder()
            .template("layout.html", "<html xmlns:t=\"https://tessera.dev\">\n<head><base href=\"https://evil.example/\">"
                + "</head><body><div t:replace=\"~{form :: f}\"></div></body></html>")
            .template("form.html", "<div xmlns:t=\"https://tessera.dev\">\n<div t:fragment=\"f\">" + FORM + "</div></div>")
            .compile();
    c.assertError("layout.html:2:13:", "on <base> is not allowed");
  }

  /** Positive: a same-origin base compiles and the form keeps its token. */
  @Test
  void sameOriginBaseKeepsToken() {
    Compilation c =
        compile("<base href=\"/app/\"><base href=\"\"><base href=\"app/\"><base target=\"_blank\">"
            + "<base t:href=\"@{/app/}\"><base t:attr=\"href=@{/app/{x}/(x=${u})}\">"
            + "<base t:href=\"${u} != null ? @{/app/} : @{/other/}\">" + FORM)
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    String out = c.render("page", Map.of("u", "x y", "t", TrustedUrl.unchecked("https://evil.example/")), ctx(forms));
    assertEquals(
        "<div>\n<base href=\"/app/\"><base href=\"\"><base href=\"app/\"><base target=\"_blank\">"
            + "<base href=\"/app/\"><base href=\"/app/x%20y/\"><base href=\"/app/\">"
            + "<form method=\"post\" action=\"/transfer\">" + INPUT + "<button formaction=\"ok\">Go</button></form>\n</div>",
        out);
  }
}
