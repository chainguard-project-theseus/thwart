package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A form that writes its own token input keeps it and gets no automatic one (#225); every other
 * POST form still gets exactly one.
 */
class CsrfOwnInputTest {
  private static final String AUTO = "<input type=\"hidden\" name=\"_csrf\" value=\"tok\" />";
  private static final String OWN = "<input type=\"hidden\" name=\"_csrf\" id=\"token\" t:value=\"tok\" />";
  private static final String OWN_OUT = "<input type=\"hidden\" name=\"_csrf\" id=\"token\" value=\"tok\" />";

  private static TestForms token(String parameterName) {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken(parameterName, "tok");
    return forms;
  }

  private static String render(String params, String form, Map<String, Object> model, Forms forms) {
    return Compilation.builder()
        .template(
            "form.html",
            "<div xmlns:t=\"https://tessera.dev\" t:params=\"String tok" + (params == null ? "" : ", " + params)
                + "\">" + form + "</div>")
        .compile()
        .assertSuccess()
        .render("form", model, ctx(forms));
  }

  private static String render(String form) {
    return render(null, form, Map.of("tok", "tok"), token("_csrf"));
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }

  @Test
  void ownTokenInputReplacesAutomaticOne() {
    assertEquals(
        "<div><form method=\"post\" action=\"/save\">" + OWN_OUT + "x</form></div>",
        render("<form method=\"post\" action=\"/save\">" + OWN + "x</form>"));
    assertEquals(
        "<div><form method=\"post\"><input name=\"_csrf\" type=\"HIDDEN\" value=\"static\" /></form></div>",
        render("<form method=\"post\"><input name=\"_csrf\" type=\"HIDDEN\" value=\"static\" /></form>"));
  }

  @Test
  void linkActionWithOwnInputHasOneToken() {
    Map<String, Object> model = new HashMap<>();
    model.put("tok", "tok");
    String out = render(null, "<form method=\"POST\" t:action=\"@{/save}\">" + OWN + "</form>", model, token("_csrf"));
    assertEquals("<div><form method=\"POST\" action=\"/save\">" + OWN_OUT + "</form></div>", out);
  }

  @Test
  void ownInputWithoutRequestTokenIsKept() {
    assertEquals(
        "<div><form method=\"post\">" + OWN_OUT + "</form></div>",
        render(null, "<form method=\"post\">" + OWN + "</form>", Map.of("tok", "tok"), new TestForms()));
  }

  /** Negative: an input under another name does not count, so the form still gets the token. */
  @Test
  void otherNameStillGetsToken() {
    String own = "<input type=\"hidden\" name=\"csrf\" value=\"v\" id=\"token\" />";
    assertEquals(
        "<div><form method=\"post\">" + AUTO + own + "</form></div>",
        render("<form method=\"post\">" + own + "</form>"));
    // Names are compared exactly, with the request's parameter name.
    String upper = "<input type=\"hidden\" name=\"_CSRF\" value=\"v\" />";
    assertEquals(
        "<div><form method=\"post\">" + AUTO + upper + "</form></div>",
        render("<form method=\"post\">" + upper + "</form>"));
    String renamed = "<input type=\"hidden\" name=\"_token\" value=\"tok\" />";
    assertEquals(
        "<div><form method=\"post\">" + renamed + OWN_OUT + "</form></div>",
        render(null, "<form method=\"post\">" + OWN + "</form>", Map.of("tok", "tok"), token("_token")));
  }

  /**
   * Negative: an input that may not be written or submitted with the form, or not as a hidden
   * input of that name, does not count: the form gets the automatic token as well.
   */
  @Test
  void inputThatMayNotBeSubmittedDoesNotCount() {
    Map<String, Object> model = new HashMap<>();
    model.put("tok", "tok");
    model.put("b", false);
    model.put("n", "_csrf");
    String[] inputs = {
      "<input type=\"hidden\" name=\"_csrf\" value=\"v\" t:if=\"b\" />",
      "<input type=\"hidden\" name=\"_csrf\" value=\"v\" t:unless=\"!b\" />",
      "<input type=\"hidden\" name=\"_csrf\" value=\"v\" t:remove=\"all\" />",
      "<input type=\"hidden\" t:name=\"n\" value=\"v\" />",
      "<input type=\"hidden\" name=\"x\" t:attr=\"name=${n}\" value=\"v\" />",
      "<input t:attr=\"type='hidden'\" name=\"_csrf\" value=\"v\" />",
      "<input type=\"text\" name=\"_csrf\" value=\"v\" />",
      "<input name=\"_csrf\" value=\"v\" />",
      "<input type=\" hidden\" name=\"_csrf\" value=\"v\" />",
      "<input type=\"hidden\" name=\"_csrf\" value=\"v\" disabled />",
      "<input type=\"hidden\" name=\"_csrf\" value=\"v\" form=\"other\" />",
      "<div><input type=\"hidden\" name=\"_csrf\" value=\"v\" /></div>",
    };
    for (String input : inputs) {
      String out = render("boolean b, String n", "<form method=\"post\">" + input + "</form>", model, token("_csrf"));
      assertEquals(1, count(out, AUTO), input + ": " + out);
    }
  }

  /** Negative: when the form's body is replaced, its own input is not written. */
  @Test
  void replacedBodyDoesNotCount() {
    assertEquals(
        "<div><form method=\"post\">" + AUTO + "</form></div>",
        render("<form method=\"post\" t:remove=\"body\">" + OWN + "</form>"));
    String out = render("<form method=\"post\" t:remove=\"all-but-first\"><p>a</p>" + OWN + "</form>");
    assertEquals(1, count(out, AUTO), out);
  }

  /** Negative: a GET form never gets the automatic token, with or without its own input. */
  @Test
  void getFormNeverGetsToken() {
    String other = "<input type=\"hidden\" name=\"q\" value=\"v\" />";
    assertEquals("<div><form method=\"get\">" + other + "</form></div>", render("<form method=\"get\">" + other + "</form>"));
    assertEquals("<div><form>" + other + "</form></div>", render("<form>" + other + "</form>"));
  }
}
