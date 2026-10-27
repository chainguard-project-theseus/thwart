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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
        "<form><textarea id=\"bio\" name=\"bio\">\na &lt;b&gt;bio&lt;/b&gt;</textarea></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void foreignTextareaBodyIsText() {
    // Inside svg and math a textarea is not RCDATA: its value is escaped as text, {{ broken (#277).
    Compilation c = compile("<svg><textarea t:field=\"*{firstName}\"></textarea></svg>");
    assertEquals(
        "<form><svg><textarea id=\"firstName\" name=\"firstName\">&lt;b&gt;{<!---->{x}}</textarea></svg></form>",
        render(c, owner(c, "<b>{{x}}", null), new TestForms()));
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
  void disabledCheckboxHasNoMarker() {
    // Thymeleaf's checkbox field processor leaves the _name marker out when the input has a
    // disabled attribute of any value (a disabled box is not submitted; the marker would reset it).
    Compilation c =
        compile(
            "<input type=\"checkbox\" disabled t:field=\"*{active}\">"
                + "<input type=\"checkbox\" disabled=\"false\" t:field=\"*{active}\">");
    assertEquals(
        "<form><input type=\"checkbox\" disabled id=\"active\" name=\"active\" value=\"true\" checked=\"checked\">"
            + "<input type=\"checkbox\" disabled=\"false\" id=\"active\" name=\"active\" value=\"true\""
            + " checked=\"checked\"></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void conditionallyDisabledCheckboxHasMarkerOnlyWhenEnabled() {
    // t:disabled runs before t:field: the marker is written only when it is false. A static
    // disabled overridden by a false t:disabled gets the marker, as in Thymeleaf.
    Compilation c =
        compile(
            "<input type=\"checkbox\" t:field=\"*{active}\" t:disabled=\"${owner.firstName == 'Ann'}\">"
                + "<input type=\"checkbox\" disabled t:disabled=\"false\" t:field=\"*{active}\">");
    assertEquals(
        "<form><input type=\"checkbox\" disabled=\"disabled\" id=\"active\" name=\"active\" value=\"true\""
            + " checked=\"checked\">"
            + "<input type=\"checkbox\" id=\"active\" name=\"active\" value=\"true\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_active\" value=\"on\" /></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
    assertEquals(
        "<form><input type=\"checkbox\" id=\"active\" name=\"active\" value=\"true\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_active\" value=\"on\" />"
            + "<input type=\"checkbox\" id=\"active\" name=\"active\" value=\"true\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_active\" value=\"on\" /></form>",
        render(c, owner(c, "Bob", null), new TestForms()));
  }

  @Test
  void checkboxDisabledThroughAttrHasNoMarker() {
    Compilation c = compile("<input type=\"checkbox\" t:attr=\"disabled=${true}\" t:field=\"*{active}\">");
    assertFalse(render(c, owner(c, "Ann", null), new TestForms()).contains("_active"));
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

  // ---- collections and arrays (#126) ----

  private static final String USER_HEAD =
      "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"User user\" t:object=\"user\">";

  /** A form over {@code User(Set<String> roles, String[] tags, List<Level> levels, Level level)}. */
  private static Compilation compileUser(String body) {
    return Compilation.builder()
        .java(
            "com.example.Level",
            "package com.example; public enum Level { LOW, HIGH;"
                + " @Override public String toString() { return name().toLowerCase(); } }")
        .java(
            "com.example.User",
            "package com.example; import java.util.List; import java.util.Set;"
                + " public record User(Set<String> roles, String[] tags, List<Level> levels, Level level) {}")
        .template("user.html", USER_HEAD + body + "</form>")
        .compile()
        .assertSuccess();
  }

  private static String renderUser(Compilation c, Set<String> roles, String[] tags, List<String> levels, String level) {
    List<Object> levelValues = new ArrayList<>();
    for (String l : levels) {
      levelValues.add(c.enumConstant("com.example.Level", l));
    }
    Object user =
        c.newInstance(
            "com.example.User", roles, tags, levelValues, level == null ? null : c.enumConstant("com.example.Level", level));
    Map<String, Object> model = new HashMap<>();
    model.put("user", user);
    return c.render("user", model, ctx(Forms.NONE));
  }

  @Test
  void checkboxesBoundToCollectionAreCheckedByContainment() {
    // Thymeleaf's SpringSelectedValueComparator: a checkbox is checked when the bound Collection
    // contains its value; every checkbox gets the _name marker, as for a boolean field.
    Compilation c =
        compileUser(
            "<input type=\"checkbox\" value=\"ROLE_ADMIN\" t:field=\"*{roles}\">"
                + "<input type=\"checkbox\" value=\"ROLE_USER\" t:field=\"*{roles}\">");
    assertEquals(
        "<form><input type=\"checkbox\" value=\"ROLE_ADMIN\" name=\"roles\">"
            + "<input type=\"hidden\" name=\"_roles\" value=\"on\" />"
            + "<input type=\"checkbox\" value=\"ROLE_USER\" name=\"roles\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_roles\" value=\"on\" /></form>",
        renderUser(c, new TreeSet<>(List.of("ROLE_USER")), new String[0], List.of(), null));
    // Neither: not the collection's display text "[ROLE_ADMIN, ROLE_USER]", and nothing for empty or null.
    String both = renderUser(c, new TreeSet<>(List.of("ROLE_ADMIN", "ROLE_USER")), new String[0], List.of(), null);
    assertEquals(2, both.split("checked=", -1).length - 1, both);
    assertFalse(renderUser(c, Set.of(), new String[0], List.of(), null).contains("checked"));
    assertFalse(renderUser(c, null, new String[0], List.of(), null).contains("checked"));
    assertFalse(renderUser(c, Set.of("ROLE_ADMINISTRATOR", "ROLE"), null, List.of(), null).contains("checked"));
  }

  @Test
  void checkboxesBoundToArrayAndRadiosBoundToCollection() {
    Compilation c =
        compileUser(
            "<input type=\"checkbox\" value=\"a\" t:field=\"*{tags}\"><input type=\"checkbox\" value=\"b\" t:field=\"*{tags}\">"
                + "<input type=\"radio\" value=\"ROLE_ADMIN\" t:field=\"*{roles}\">"
                + "<input type=\"radio\" value=\"ROLE_USER\" t:field=\"*{roles}\">");
    assertEquals(
        "<form><input type=\"checkbox\" value=\"a\" name=\"tags\"><input type=\"hidden\" name=\"_tags\" value=\"on\" />"
            + "<input type=\"checkbox\" value=\"b\" name=\"tags\" checked=\"checked\">"
            + "<input type=\"hidden\" name=\"_tags\" value=\"on\" />"
            + "<input type=\"radio\" value=\"ROLE_ADMIN\" name=\"roles\" checked=\"checked\">"
            + "<input type=\"radio\" value=\"ROLE_USER\" name=\"roles\"></form>",
        renderUser(c, Set.of("ROLE_ADMIN"), new String[] {"b", "c"}, List.of(), null));
  }

  @Test
  void enumsMatchByNameOrToString() {
    // An enum element matches its name and its toString(), as in SpringSelectedValueComparator.
    Compilation c =
        compileUser(
            "<input type=\"checkbox\" value=\"LOW\" t:field=\"*{levels}\">"
                + "<input type=\"checkbox\" value=\"high\" t:field=\"*{levels}\">"
                + "<input type=\"radio\" value=\"low\" t:field=\"*{level}\">"
                + "<input type=\"radio\" value=\"HIGH\" t:field=\"*{level}\">");
    String out = renderUser(c, Set.of(), null, List.of("HIGH"), "LOW");
    assertTrue(out.contains("value=\"LOW\" name=\"levels\"><"), out);
    assertTrue(out.contains("value=\"high\" name=\"levels\" checked=\"checked\">"), out);
    assertTrue(out.contains("value=\"low\" name=\"level\" checked=\"checked\">"), out);
    assertTrue(out.contains("value=\"HIGH\" name=\"level\"></form>"), out);
  }

  @Test
  void multipleSelectMarksEveryContainedOption() {
    Compilation c =
        compileUser(
            "<select multiple t:field=\"*{roles}\"><option value=\"ROLE_ADMIN\">A</option>"
                + "<option value=\"ROLE_USER\">U</option><option value=\"ROLE_GUEST\">G</option></select>");
    assertEquals(
        "<form><select multiple id=\"roles\" name=\"roles\"><option value=\"ROLE_ADMIN\" selected=\"selected\">A</option>"
            + "<option value=\"ROLE_USER\" selected=\"selected\">U</option><option value=\"ROLE_GUEST\">G</option>"
            + "</select></form>",
        renderUser(c, new TreeSet<>(List.of("ROLE_ADMIN", "ROLE_USER")), null, List.of(), null));
    assertFalse(renderUser(c, null, null, List.of(), null).contains("selected"));
  }

  @Test
  void disabledCollectionCheckboxHasNoMarker() {
    Compilation c = compileUser("<input type=\"checkbox\" disabled value=\"ROLE_USER\" t:field=\"*{roles}\">");
    assertEquals(
        "<form><input type=\"checkbox\" disabled value=\"ROLE_USER\" name=\"roles\" checked=\"checked\"></form>",
        renderUser(c, Set.of("ROLE_USER"), null, List.of(), null));
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
    compileForm(HEAD + "><input t:field=\"*{age}\" t:errorclass=\"x&quot; onclick=&quot;y\"></form>")
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
