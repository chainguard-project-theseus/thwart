package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
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
 * The method of a {@code <form>} decides whether it gets the CSRF token, so it must be static
 * (closed shape; replaces #209's render-time check).
 */
class CsrfComputedMethodTest {
  private static final String INPUT = "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />";

  private static Compilation compile(String formAttrs) {
    return Compilation.builder()
        .template(
            "form.html",
            "<div xmlns:t=\"https://tessera.dev\" t:params=\"String m, boolean edit\"><form " + formAttrs
                + ">x</form></div>")
        .compile();
  }

  private static String render(String formAttrs) {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    return compile(formAttrs).assertSuccess().render("form", Map.of("m", "post", "edit", true), ctx(forms));
  }

  /** Negative: every way to compute a form's method is a build error with a fix-it. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "t:method=\"'post'\"",
        "t:method=\"m\"",
        "t:method=\"edit ? 'post' : 'get'\"",
        "method=\"get\" t:method=\"'post'\"",
        "method=\"post\" t:method=\"'get'\"",
        "action=\"/a\" t:attr=\"method='post'\"",
        "t:METHOD=\"m\"",
      })
  void computedMethodFails(String attrs) {
    Compilation c = compile(attrs);
    c.assertError("form.html:1:", "is not allowed: the method decides whether the form gets the CSRF token",
        "write method=\"post\" or method=\"get\"");
    assertNull(c.generatedSource("tessera.generated.FormTemplate"), "nothing may be generated");
  }

  /** A computed formmethod-like name elsewhere is unaffected; only {@code <form>}'s method is. */
  @Test
  void methodOnOtherElementsIsNotAFormMethod() {
    compile("method=\"post\"><input t:name=\"m\"").assertSuccess();
  }

  /**
   * Negative: a static method is read as a browser reads it. {@code method=" post"} submits as GET,
   * so it gets no token; entity-encoded {@code post} is still POST.
   */
  @Test
  void staticMethodUsesBrowserRule() {
    for (String m : new String[] {" post", "post ", "\tpost", "poſt", "get", "dialog", ""}) {
      String out = render("method=\"" + m + "\"");
      assertFalse(out.contains("_csrf"), m + ": " + out);
    }
    assertTrue(render("method=\"&#112;OST\"").contains(INPUT));
    assertTrue(render("method=\"Post\"").contains(INPUT));
  }
}
