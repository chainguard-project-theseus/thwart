package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static dev.tessera.compiler.codegen.attr.FormFixture.model;
import static dev.tessera.compiler.codegen.attr.FormFixture.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** t:object, t:field, t:errors, t:errorclass and CSRF injection, end to end. */
class FormsTest {
  private static final String HEAD =
      "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"owner\"";

  private static Compilation compileForm(String template) {
    return model().template("form.html", template).compile();
  }

  private static Compilation compile(String body) {
    return compileForm(HEAD + ">" + body + "</form>").assertSuccess();
  }

  private static String render(Compilation c, Object owner, Forms forms) {
    Map<String, Object> model = new HashMap<>();
    model.put("owner", owner);
    return c.render("form", model, ctx(forms));
  }

  // ---- t:field ----

  @Test
  void textHiddenNumberInputs() {
    Compilation c =
        compile(
            "<input type=\"text\" t:field=\"*{firstName}\"><input type=\"hidden\" t:field=\"firstName\">"
                + "<input type=\"number\" id=\"x\" name=\"ignored\" value=\"ignored\" t:field=\"*{age}\">");
    assertEquals(
        "<form><input type=\"text\" id=\"firstName\" name=\"firstName\" value=\"Ann\">"
            + "<input type=\"hidden\" id=\"firstName\" name=\"firstName\" value=\"Ann\">"
            + "<input type=\"number\" id=\"x\" name=\"age\" value=\"42\"></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void valueIsEscapedAndRejectedValueWins() {
    Compilation c = compile("<input t:field=\"*{firstName}\">");
    assertEquals(
        "<form><input id=\"firstName\" name=\"firstName\" value=\"&quot;&gt;&lt;script&gt;\"></form>",
        render(c, owner(c, "\"><script>", null), new TestForms()));
    TestForms forms = new TestForms();
    forms.rejected.put("firstName", "' onmouseover='x");
    assertEquals(
        "<form><input id=\"firstName\" name=\"firstName\" value=\"&#39; onmouseover=&#39;x\"></form>",
        render(c, owner(c, "Ann", null), forms).replace("&#x27;", "&#39;"));
  }

  @Test
  void nestedPath() {
    Compilation c = compile("<input t:field=\"*{address.city}\">");
    assertEquals(
        "<form><input id=\"address.city\" name=\"address.city\" value=\"Madison\"></form>",
        render(c, owner(c, "Ann", "Madison"), new TestForms()));
    assertEquals(
        "<form><input id=\"address.city\" name=\"address.city\" value=\"\"></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void passwordNeverEchoesValue() {
    Compilation c = compile("<input type=\"password\" value=\"x\" t:field=\"*{password}\">");
    assertEquals(
        "<form><input type=\"password\" id=\"password\" name=\"password\"></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void textareaBody() {
    Compilation c = compile("<textarea t:field=\"*{bio}\">placeholder</textarea>");
    assertEquals(
        "<form><textarea id=\"bio\" name=\"bio\">a &lt;b&gt;bio&lt;/b&gt;</textarea></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void checkboxAndRadio() {
    Compilation c =
        compile(
            "<input type=\"checkbox\" t:field=\"*{active}\">"
                + "<input type=\"radio\" value=\"cat\" t:field=\"*{pet}\"><input type=\"radio\" value=\"dog\" t:field=\"*{pet}\">");
    String out = render(c, owner(c, "Ann", null), new TestForms());
    assertEquals(
        "<form><input type=\"checkbox\" id=\"active\" name=\"active\" value=\"true\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_active\" value=\"on\" />"
            + "<input type=\"radio\" value=\"cat\" name=\"pet\"><input type=\"radio\" value=\"dog\" name=\"pet\" checked=\"checked\">"
            + "</form>",
        out);
    TestForms forms = new TestForms();
    forms.rejected.put("pet", "cat");
    assertTrue(render(c, owner(c, "Ann", null), forms).contains("value=\"cat\" name=\"pet\" checked=\"checked\">"));
  }

  @Test
  void radioWithoutValueIsAnError() {
    compileForm(HEAD + "><input type=\"radio\" t:field=\"*{pet}\"></form>").assertError("needs a value attribute");
  }

  @Test
  void selectMarksMatchingOption() {
    Compilation c =
        compile(
            "<select t:field=\"*{pet}\"><option value=\"cat\" selected>Cat</option><option value=\"dog\">Dog</option>"
                + "<option>none</option></select>");
    assertEquals(
        "<form><select id=\"pet\" name=\"pet\"><option value=\"cat\">Cat</option>"
            + "<option value=\"dog\" selected=\"selected\">Dog</option><option>none</option></select></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void dynamicOptionValueAndClassAppend() {
    Compilation c =
        compile(
            "<select t:field=\"*{pet}\"><option t:value=\"${owner.pet}\">same</option></select>"
                + "<input class=\"a\" t:classappend=\"'b'\" t:field=\"*{age}\" t:errorclass=\"err\">");
    TestForms forms = new TestForms();
    forms.errors.put("age", List.of("bad"));
    assertEquals(
        "<form><select id=\"pet\" name=\"pet\"><option value=\"dog\" selected=\"selected\">same</option></select>"
            + "<input class=\"a b err\" id=\"age\" name=\"age\" value=\"42\"></form>",
        render(c, owner(c, "Ann", null), forms));
  }

  @Test
  void optionsOutsideFieldSelectAreUntouched() {
    Compilation c = compile("<select name=\"x\"><option value=\"dog\" selected>Dog</option></select>");
    assertEquals(
        "<form><select name=\"x\"><option value=\"dog\" selected>Dog</option></select></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void nullBoundValueSelectsNoOption() {
    // Spring's SelectedValueComparator: a null value matches no option, not the "" placeholder.
    Compilation c =
        compile(
            "<select t:field=\"*{address.city}\"><option value=\"\">Choose</option>"
                + "<option value=\"Oslo\">Oslo</option></select>");
    assertEquals(
        "<form><select id=\"address.city\" name=\"address.city\"><option value=\"\">Choose</option>"
            + "<option value=\"Oslo\">Oslo</option></select></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
    assertEquals(
        "<form><select id=\"address.city\" name=\"address.city\"><option value=\"\">Choose</option>"
            + "<option value=\"Oslo\" selected=\"selected\">Oslo</option></select></form>",
        render(c, owner(c, "Ann", "Oslo"), new TestForms()));
    assertEquals(
        "<form><select id=\"address.city\" name=\"address.city\"><option value=\"\" selected=\"selected\">"
            + "Choose</option><option value=\"Oslo\">Oslo</option></select></form>",
        render(c, owner(c, "Ann", ""), new TestForms()));
    // After a failed bind the rejected value is compared, even when the model value is null.
    TestForms rejected = new TestForms();
    rejected.rejected.put("address.city", "Oslo");
    assertEquals(
        "<form><select id=\"address.city\" name=\"address.city\"><option value=\"\">Choose</option>"
            + "<option value=\"Oslo\" selected=\"selected\">Oslo</option></select></form>",
        render(c, owner(c, "Ann", null), rejected));
  }

  @Test
  void explicitSelectedOnOptionWins() {
    Compilation c =
        compile(
            "<select t:field=\"*{pet}\"><option value=\"cat\" t:selected=\"${true}\">Cat</option>"
                + "<option value=\"dog\" t:selected=\"${false}\">Dog</option>"
                + "<option value=\"fish\">Fish</option></select>");
    assertEquals(
        "<form><select id=\"pet\" name=\"pet\"><option value=\"cat\" selected=\"selected\">Cat</option>"
            + "<option value=\"dog\">Dog</option><option value=\"fish\">Fish</option></select></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void unknownFieldIsBuildErrorNamingType() {
    compileForm(HEAD + ">\n<input t:field=\"*{lastName}\"></form>")
        .assertError("form.html:2:", "'lastName' is not a property of com.example.Owner");
    compileForm(HEAD + "><input t:field=\"*{address.zip}\"></form>")
        .assertError("'zip' is not a property of com.example.Address");
  }

  @Test
  void fieldOutsideObjectIsBuildError() {
    compileForm(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<input t:field=\"*{firstName}\"></form>")
        .assertError("t:field can only be used inside t:object");
  }

  @Test
  void fieldNeedsPlainPath() {
    compileForm(HEAD + "><input t:field=\"*{firstName} + 'x'\"></form>").assertError("needs a field path");
    compileForm(HEAD + "><input t:field=\"*{address?.city}\"></form>").assertError("needs a field path");
    compileForm(HEAD + "><div t:field=\"*{firstName}\"></div></form>").assertError("t:field works on <input>");
    compileForm(HEAD + "><input t:field=\"*{__${x}__}\"></form>").assertError("form.html:1:");
  }

  @Test
  void objectMustNameVariable() {
    compileForm(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\""
                + " t:object=\"owner.address\"></form>")
        .assertError("t:object must name a variable");
    compileForm(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"nope\"></form>")
        .assertError("unknown identifier 'nope'");
  }

  @Test
  void selectionExpressionsInsideObject() {
    // t:object on a non-form element; *{...} is resolved by the expression compiler (#4).
    Compilation c =
        compileForm(
                "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                    + "<div t:object=\"${owner}\"><input t:field=\"firstName\"></div></form>")
            .assertSuccess();
    assertEquals(
        "<form><div><input id=\"firstName\" name=\"firstName\" value=\"Ann\"></div></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  // ---- t:errors / t:errorclass ----

  @Test
  void errorsRenderEscapedJoinedOrRemoveElement() {
    Compilation c =
        compile(
            "<span class=\"help\" t:errors=\"*{firstName}\">Error</span><p t:errors=\"*\">All</p>"
                + "<i t:errors=\"address.city\">x</i>");
    assertEquals("<form></form>", render(c, owner(c, "Ann", null), new TestForms()));
    TestForms forms = new TestForms();
    forms.errors.put("firstName", List.of("is <required>", "too short"));
    assertEquals(
        "<form><span class=\"help\">is &lt;required&gt;<br />too short</span>"
            + "<p>is &lt;required&gt;<br />too short</p></form>",
        render(c, owner(c, "Ann", null), forms));
  }

  @Test
  void errorsNegative() {
    compileForm(HEAD + "><span t:errors=\"*{nope}\"></span></form>")
        .assertError("'nope' is not a property of com.example.Owner");
    compileForm(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<span t:errors=\"*\"></span></form>")
        .assertError("t:errors can only be used inside t:object");
  }

  @Test
  void errorsInScriptOrStyleIsRejected() {
    // Error text (which can echo user input) would otherwise run as live JavaScript or CSS.
    compileForm(HEAD + "><script t:errors=\"*\"></script></form>")
        .assertError(
            "form.html:1:", "t:errors is only allowed on elements whose content is HTML text, not <script> or <style>");
    compileForm(HEAD + "><style t:errors=\"*{firstName}\"></style></form>")
        .assertError("t:errors is only allowed on elements whose content is HTML text, not <script> or <style>");
  }

  @Test
  void errorClassAppendsWhenFieldHasErrors() {
    Compilation c =
        compile(
            "<input class=\"form-control\" t:field=\"*{firstName}\" t:errorclass=\"is-invalid\">"
                + "<input t:field=\"*{age}\" t:errorclass=\"'bad field'\">");
    assertEquals(
        "<form><input class=\"form-control\" id=\"firstName\" name=\"firstName\" value=\"Ann\">"
            + "<input id=\"age\" name=\"age\" value=\"42\"></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
    TestForms forms = new TestForms();
    forms.errors.put("firstName", List.of("bad"));
    forms.errors.put("age", List.of("bad"));
    assertEquals(
        "<form><input class=\"form-control is-invalid\" id=\"firstName\" name=\"firstName\" value=\"Ann\">"
            + "<input id=\"age\" name=\"age\" value=\"42\" class=\"bad field\"></form>",
        render(c, owner(c, "Ann", null), forms));
  }

  @Test
  void errorClassNegative() {
    compileForm(HEAD + "><input t:errorclass=\"err\"></form>").assertError("t:errorclass needs t:field");
    compileForm(HEAD + "><input t:field=\"*{age}\" t:errorclass=\"x\\\" onclick=\\\"y\"></form>")
        .assertError("t:errorclass needs literal class names");
    compileForm(HEAD + "><input t:field=\"*{age}\" t:errorclass=\"${owner.firstName}\"></form>")
        .assertError("t:errorclass needs literal class names");
  }

  @Test
  void fieldsHasErrorsInTemplates() {
    Compilation c =
        compile(
            "<b t:if=\"${fields.hasErrors('firstName')}\">F</b><i t:if=\"${fields.hasErrors('*')}\">A</i>"
                + "<u t:text=\"${fields.errors('firstName').size()}\">0</u>");
    assertEquals("<form><u>0</u></form>", render(c, owner(c, "Ann", null), new TestForms()));
    TestForms forms = new TestForms();
    forms.errors.put("firstName", List.of("bad"));
    assertEquals("<form><b>F</b><i>A</i><u>1</u></form>", render(c, owner(c, "Ann", null), forms));
  }

  // ---- CSRF ----

  @Test
  void csrfOnlyInPostFormsWithToken() {
    String t =
        "<div xmlns:t=\"https://tessera.dev\"><form method=\"POST\" action=\"/a\">x</form>"
            + "<form method=\"get\">y</form><form>z</form></div>";
    Compilation c = model().template("form.html", t).compile().assertSuccess();
    TestForms forms = new TestForms();
    assertEquals(
        "<div><form method=\"POST\" action=\"/a\">x</form><form method=\"get\">y</form><form>z</form></div>",
        c.render("form", Map.of(), ctx(forms)));
    forms.token = new Forms.CsrfToken("_csrf", "t\"><script>");
    String out = c.render("form", Map.of(), ctx(forms));
    assertEquals(
        "<div><form method=\"POST\" action=\"/a\"><input type=\"hidden\" name=\"_csrf\" value=\"t&quot;&gt;&lt;script&gt;\" />x</form>"
            + "<form method=\"get\">y</form><form>z</form></div>",
        out);
    assertFalse(out.contains("<script>"));
  }
}
