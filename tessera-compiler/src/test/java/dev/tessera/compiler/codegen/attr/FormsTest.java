package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** t:object, t:field, t:errors, t:errorclass and CSRF injection, end to end. */
class FormsTest {
  private static final String HEAD =
      "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"owner\"";

  private static Compilation.Builder model() {
    return Compilation.builder()
        .java("com.example.Address", "package com.example; public record Address(String city) {}")
        .java(
            "com.example.Owner",
            "package com.example; public record Owner(String firstName, int age, boolean active, Address address,"
                + " String pet, String bio, String password) {}");
  }

  private static Compilation compile(String body) {
    return compile(HEAD + ">" + body + "</form>", true);
  }

  private static Compilation compile(String template, boolean ok) {
    Compilation c = model().template("form.html", template).compile();
    assumeFalse(c.errors().stream().anyMatch(e -> e.contains("TODO(#")), () -> "stage not implemented: " + c.errors());
    return ok ? c.assertSuccess() : c;
  }

  private static Object owner(Compilation c, String firstName, String city) {
    Object address = city == null ? null : c.newInstance("com.example.Address", city);
    return c.newInstance("com.example.Owner", firstName, 42, true, address, "dog", "a <b>bio</b>", "secret");
  }

  /** A Forms with fixed rejected values, errors and CSRF token. */
  static final class TestForms implements Forms {
    final Map<String, String> rejected = new HashMap<>();
    final Map<String, List<String>> errors = new HashMap<>();
    CsrfToken token;

    @Override
    public String value(String objectName, String path, Object currentValue) {
      assertEquals("owner", objectName);
      return rejected.containsKey(path) ? rejected.get(path) : Escapers.toText(currentValue);
    }

    @Override
    public List<String> errors(String objectName, String path) {
      assertEquals("owner", objectName);
      if (path.equals("*")) {
        return errors.values().stream().flatMap(List::stream).toList();
      }
      return errors.getOrDefault(path, List.of());
    }

    @Override
    public Optional<CsrfToken> csrfToken() {
      return Optional.ofNullable(token);
    }
  }

  private static RenderContext ctx(Forms forms) {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), forms);
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
    compile(HEAD + "><input type=\"radio\" t:field=\"*{pet}\"></form>", false).assertError("needs a value attribute");
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
  void unknownFieldIsBuildErrorNamingType() {
    compile(HEAD + ">\n<input t:field=\"*{lastName}\"></form>", false)
        .assertError("form.html:2:", "'lastName' is not a property of com.example.Owner");
    compile(HEAD + "><input t:field=\"*{address.zip}\"></form>", false)
        .assertError("'zip' is not a property of com.example.Address");
  }

  @Test
  void fieldOutsideObjectIsBuildError() {
    compile(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<input t:field=\"*{firstName}\"></form>",
            false)
        .assertError("t:field can only be used inside t:object");
  }

  @Test
  void fieldNeedsPlainPath() {
    compile(HEAD + "><input t:field=\"*{firstName} + 'x'\"></form>", false).assertError("needs a field path");
    compile(HEAD + "><input t:field=\"*{address?.city}\"></form>", false).assertError("needs a field path");
    compile(HEAD + "><div t:field=\"*{firstName}\"></div></form>", false).assertError("t:field works on <input>");
    compile(HEAD + "><input t:field=\"*{__${x}__}\"></form>", false).assertError("form.html:1:");
  }

  @Test
  void objectMustNameVariable() {
    compile(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\""
                + " t:object=\"owner.address\"></form>",
            false)
        .assertError("t:object must name a variable");
    compile(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"nope\"></form>",
            false)
        .assertError("unknown identifier 'nope'");
  }

  @Test
  void selectionExpressionsInsideObject() {
    // t:object on a non-form element; *{...} is resolved by the expression compiler (#4).
    Compilation c =
        compile(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<div t:object=\"${owner}\"><input t:field=\"firstName\"></div></form>",
            true);
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
    compile(HEAD + "><span t:errors=\"*{nope}\"></span></form>", false)
        .assertError("'nope' is not a property of com.example.Owner");
    compile(
            "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<span t:errors=\"*\"></span></form>",
            false)
        .assertError("t:errors can only be used inside t:object");
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
    compile(HEAD + "><input t:errorclass=\"err\"></form>", false).assertError("t:errorclass needs t:field");
    compile(HEAD + "><input t:field=\"*{age}\" t:errorclass=\"x\\\" onclick=\\\"y\"></form>", false)
        .assertError("t:errorclass needs literal class names");
    compile(HEAD + "><input t:field=\"*{age}\" t:errorclass=\"${owner.firstName}\"></form>", false)
        .assertError("t:errorclass needs literal class names");
  }

  @Test
  void fieldsHasErrorsInTemplates() {
    // Needs t:if (#12) and t:text (#11); skipped until they land.
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
