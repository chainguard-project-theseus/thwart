package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.codegen.attr.FormFixture.ctx;
import static dev.chainguard.thwart.compiler.codegen.attr.FormFixture.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.chainguard.thwart.compiler.codegen.attr.FormFixture.TestForms;
import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code Field} fragment parameters (#43): reusable form-field fragments whose field path is a
 * string literal checked at the call site against the caller's {@code t:object}.
 */
class FieldFragmentsTest {
  private static final String NS = "xmlns:t=\"https://thwart.chainguard.dev\"";

  /** The petclinic inputField/selectField pattern, plus a textarea and a forwarding fragment. */
  private static final String FIELDS =
      "<div " + NS + ">"
          + "<t:block t:fragment=\"input(String label, Field name, String type)\">"
          + "<div t:with=\"valid=!fields.hasErrors(name)\" t:class=\"'group' + (valid ? '' : ' has-error')\">"
          + "<label t:for=\"name\" t:text=\"label\">L</label>"
          + "<input t:if=\"type == 'text'\" type=\"text\" t:field=\"name\" t:classappend=\"fields.hasErrors(name) ? 'bad'\" />"
          + "<input t:if=\"type == 'date'\" type=\"date\" t:field=\"name\" />"
          + "<span t:errors=\"name\">E</span></div></t:block>"
          + "<t:block t:fragment=\"select(Field name, List<String> items)\"><select t:field=\"${name}\">"
          + "<option t:each=\"i : items\" t:value=\"i\" t:text=\"i\">x</option></select></t:block>"
          + "<t:block t:fragment=\"area(Field name)\"><textarea t:field=\"name\"></textarea></t:block>"
          + "<t:block t:fragment=\"forward(Field name)\"><t:block t:replace=\"~{:: area(name)}\"></t:block></t:block>"
          + "</div>";

  private static final String HEAD =
      "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"owner\">";

  private static Compilation.Builder model() {
    return FormFixture.model().template("fragments/fields.html", FIELDS);
  }

  private static Compilation compile(String form) {
    return model().template("form.html", form).compile();
  }

  private static String render(Compilation c, Object owner, TestForms forms) {
    return c.render("form", Map.of("owner", owner), ctx(forms));
  }

  @Test
  void inputFragmentRendersLikeInlinedMarkup() {
    Compilation c =
        compile(
                HEAD
                    + "<div t:replace=\"~{fragments/fields :: input('First', 'firstName', 'text')}\"></div>"
                    + "<div t:replace=\"~{fragments/fields :: input(label='City', name='address.city', type='date')}\"></div>"
                    + "</form>")
            .assertSuccess();
    assertEquals(
        "<form><div class=\"group\"><label for=\"firstName\">First</label>"
            + "<input type=\"text\" id=\"firstName\" name=\"firstName\" value=\"Ann\" /></div>"
            + "<div class=\"group\"><label for=\"address.city\">City</label>"
            + "<input type=\"date\" id=\"address.city\" name=\"address.city\" value=\"Madison\" /></div></form>",
        render(c, owner(c, "Ann", "Madison"), new TestForms()));

    TestForms forms = new TestForms();
    forms.errors.put("firstName", List.of("is <required>"));
    forms.rejected.put("firstName", "\"><script>");
    assertEquals(
        "<form><div class=\"group has-error\"><label for=\"firstName\">First</label>"
            + "<input type=\"text\" id=\"firstName\" name=\"firstName\" value=\"&quot;&gt;&lt;script&gt;\" class=\"bad\" />"
            + "<span>is &lt;required&gt;</span></div>"
            + "<div class=\"group\"><label for=\"address.city\">City</label>"
            + "<input type=\"date\" id=\"address.city\" name=\"address.city\" value=\"\" /></div></form>",
        render(c, owner(c, "Ann", null), forms));
  }

  @Test
  void selectTextareaAndForwardedField() {
    Compilation c =
        compile(
                "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner, List<String> pets\" t:object=\"owner\">"
                    + "<div t:replace=\"~{fragments/fields :: select('pet', pets)}\"></div>"
                    + "<div t:replace=\"~{fragments/fields :: forward('bio')}\"></div>"
                    + "</form>")
            .assertSuccess();
    String out =
        c.render(
            "form",
            Map.of("owner", owner(c, "Ann", null), "pets", List.of("cat", "dog")),
            ctx(new TestForms()));
    assertEquals(
        "<form><select id=\"pet\" name=\"pet\"><option value=\"cat\">cat</option>"
            + "<option value=\"dog\" selected=\"selected\">dog</option></select>"
            + "<textarea id=\"bio\" name=\"bio\">\na &lt;b&gt;bio&lt;/b&gt;</textarea></form>",
        out);
  }

  @Test
  void optionsInsertedIntoFieldSelectAreRejected() {
    // #657: only the options written inside a <select t:field> see the field; inserted ones would
    // render with nothing selected, so any t:insert or t:replace in it is a build error.
    Compilation.Builder b =
        model()
            .template(
                "opts.html",
                "<div " + NS + "><t:block t:fragment=\"opts(List<String> items)\">"
                    + "<option t:each=\"i : items\" t:value=\"i\" t:text=\"i\">x</option></t:block></div>");
    String head =
        "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner, List<String> pets\" t:object=\"owner\">\n";
    String rule = " inside a <select t:field> is not supported: inserted options don't see the field";
    String[][] cases = {
      {"<select t:field=\"*{pet}\"><t:block t:replace=\"~{opts :: opts(pets)}\"></t:block></select>", "2:35", "t:replace"},
      {"<select t:field=\"*{pet}\" t:insert=\"~{opts :: opts(pets)}\"></select>", "2:26", "t:insert"},
      {"<select t:field=\"*{pet}\" t:replace=\"~{opts :: opts(pets)}\"></select>", "2:26", "t:replace"},
      {"<select t:field=\"*{pet}\"><optgroup label=\"g\"><option t:insert=\"~{opts :: opts(pets)}\"></option>"
          + "</optgroup></select>", "2:54", "t:insert"},
      {"<select t:field=\"*{pet}\"><t:block t:if=\"${pets != null}\"><t:block t:insert=\"~{opts :: opts(pets)}\">"
          + "</t:block></t:block></select>", "2:67", "t:insert"},
    };
    // (A Fragment value, a layout's slot, is already an error anywhere in <select> content.)
    for (String[] c : cases) {
      Compilation r = b.template("form.html", head + c[0] + "</form>").compile();
      r.assertError("form.html:" + c[1], c[2] + rule);
      assertEquals(1, r.errors().size(), r.errors().toString());
    }
    // In a Field fragment too, and the error names the fragment's template.
    model()
        .template("opts.html", "<div " + NS + "><option t:fragment=\"opt\">a</option></div>")
        .template(
            "sel.html",
            "<div " + NS + ">\n<t:block t:fragment=\"sel(Field name)\"><select t:field=\"${name}\">"
                + "<option t:replace=\"~{opts :: opt}\"></option></select></t:block></div>")
        .template("form.html", head + "<div t:replace=\"~{sel :: sel('pet')}\"></div></form>")
        .compile()
        .assertError("sel.html:2:", "t:replace" + rule);
    // The guide's recipe (templates.md, Rendering): an htmx partial of a <select t:field> is the whole select
    // under its t:object, rendered as the view "sel :: sel".
    Compilation ok =
        model()
            .template(
                "sel.html",
                "<div " + NS + " t:import=\"com.example.*\"><t:block t:fragment=\"sel(Owner owner, List<String> pets)\""
                    + " t:object=\"owner\"><select t:field=\"*{pet}\"><option t:each=\"i : pets\" t:value=\"i\""
                    + " t:text=\"i\">x</option></select></t:block></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<select id=\"pet\" name=\"pet\"><option value=\"cat\">cat</option>"
            + "<option value=\"dog\" selected=\"selected\">dog</option></select>",
        ok.render("sel :: sel", Map.of("owner", owner(ok, "Ann", null), "pets", List.of("cat", "dog")), ctx(new TestForms())));
  }

  @Test
  void fieldArgumentAsFragmentValue() {
    // A Field argument inside a ~{...} Fragment argument is evaluated by the caller, in its t:object.
    Compilation c =
        model()
            .template(
                "wrap.html",
                "<div " + NS + "><p t:fragment=\"wrap(Fragment body)\"><t:block t:replace=\"body\"></t:block></p></div>")
            .template(
                "form.html",
                HEAD + "<div t:replace=\"~{wrap :: wrap(~{fragments/fields :: area('bio')})}\"></div></form>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<form><p><textarea id=\"bio\" name=\"bio\">\na &lt;b&gt;bio&lt;/b&gt;</textarea></p></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  // ---- call site: the path is a literal checked against the caller's t:object ----

  @Test
  void unknownPathIsBuildErrorAtCallSite() {
    compile(HEAD + "\n<div t:replace=\"~{fragments/fields :: area('lastName')}\"></div></form>")
        .assertError("form.html:2:", "'lastName' is not a property of com.example.Owner");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('address.zip')}\"></div></form>")
        .assertError("'zip' is not a property of com.example.Address");
  }

  @Test
  void nonLiteralPathIsRejected() {
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('first' + 'Name')}\"></div></form>")
        .assertError("has type Field: pass a field path as a string literal");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area(owner.firstName)}\"></div></form>")
        .assertError("has type Field: pass a field path as a string literal");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area(*{firstName})}\"></div></form>")
        .assertError("has type Field: pass a field path as a string literal");
    compile(
            "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner, String f\" t:object=\"owner\">"
                + "<div t:replace=\"~{fragments/fields :: area(f)}\"></div></form>")
        .assertError("has type Field: pass a field path as a string literal");
  }

  @Test
  void pathsThatAreNotPlainPropertyPathsAreRejected() {
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('*')}\"></div></form>")
        .assertError("'*' is not a field path");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('class')}\"></div></form>")
        .assertError("form.html:1:");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('address.')}\"></div></form>")
        .assertError("is not a field path");
    compile(HEAD + "<div t:replace=\"~{fragments/fields :: area('a b')}\"></div></form>")
        .assertError("is not a field path");
  }

  @Test
  void callOutsideObjectIsBuildError() {
    compile(
            "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner\">"
                + "<div t:replace=\"~{fragments/fields :: area('bio')}\"></div></form>")
        .assertError("is a form field, so the call must be inside t:object");
  }

  // ---- inside the fragment: a Field is usable only by the form attributes ----

  @Test
  void fieldValueIsNotReadable() {
    String head = "<div " + NS + "><p t:fragment=\"f(Field name)\" ";
    for (String attr :
        List.of("t:text=\"name.value\"", "t:text=\"name.value()\"", "t:text=\"name.objectName\"", "t:text=\"name.getClass()\"")) {
      Compilation c = Compilation.builder().template("frag.html", head + attr + ">x</p></div>").compile();
      assertFalse(c.success(), attr);
    }
  }

  @Test
  void fieldIsNotJson() {
    Compilation.builder()
        .template("frag.html", "<div " + NS + "><p t:fragment=\"f(Field name)\"><script>var n = [[${name}]];</script></p></div>")
        .compile()
        .assertError("JSON");
  }

  @Test
  void formAttributesNeedAFieldNotAString() {
    String head = "<div " + NS + "><t:block t:fragment=\"f(String name)\">";
    Compilation.builder()
        .template("frag.html", head + "<input t:field=\"name\" /></t:block></div>")
        .compile()
        .assertError("t:field can only be used inside t:object");
    Compilation.builder()
        .template("frag.html", head + "<span t:errors=\"name\"></span></t:block></div>")
        .compile()
        .assertError("t:errors can only be used inside t:object");
    Compilation.builder()
        .template("frag.html", head + "<b t:if=\"fields.hasErrors(name)\">x</b></t:block></div>")
        .compile()
        .assertError("fields.hasErrors(...) can only be used inside t:object");
  }

  /** A fragment sees only its parameters, so not its caller's t:object; a Field parameter carries the path. */
  @Test
  void fragmentDoesNotSeeTheCallersObject() {
    model()
        .template("frag.html", "<div " + NS + "><p t:fragment=\"f\" t:text=\"*{firstName}\">x</p></div>")
        .template("form.html", HEAD + "<t:block t:replace=\"~{frag :: f}\"></t:block></form>")
        .compile()
        .assertError("frag.html:1:", "*{firstName} can only be used inside t:object", "a fragment doesn't see its"
            + " caller's t:object, so take the field as a Field parameter");
    Compilation c = model()
        .template("frag.html", "<div " + NS + "><input t:fragment=\"f(Field name)\" t:field=\"name\" /></div>")
        .template("form.html", HEAD + "<t:block t:replace=\"~{frag :: f('firstName')}\"></t:block></form>")
        .compile()
        .assertSuccess();
    assertEquals("<form><input id=\"firstName\" name=\"firstName\" value=\"Ann\" /></form>",
        render(c, owner(c, "Ann", null), new TestForms()));
  }

  @Test
  void fieldIsAFragmentParameterTypeOnly() {
    Compilation.builder()
        .template("page.html", "<p " + NS + " t:params=\"Field name\">x</p>")
        .compile()
        .assertError("page.html:1:", "parameter 'name' has type Field, which only fragment parameters may have");
  }

  @Test
  void fieldsErrorsWithField() {
    Compilation c =
        model()
            .template(
                "errs.html",
                "<div " + NS + "><ul t:fragment=\"errs(Field name)\"><li t:each=\"e : fields.errors(name)\" t:text=\"e\">e</li></ul></div>")
            .template("form.html", HEAD + "<div t:replace=\"~{errs :: errs('age')}\"></div></form>")
            .compile()
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.errors.put("age", List.of("too <old>", "not a number"));
    assertEquals(
        "<form><ul><li>too &lt;old&gt;</li><li>not a number</li></ul></form>", render(c, owner(c, "Ann", null), forms));
  }
}
