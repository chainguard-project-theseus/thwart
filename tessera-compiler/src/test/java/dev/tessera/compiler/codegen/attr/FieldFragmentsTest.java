package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static dev.tessera.compiler.codegen.attr.FormFixture.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code Field} fragment parameters (#43): reusable form-field fragments whose field path is a
 * string literal checked at the call site against the caller's {@code t:object}.
 */
class FieldFragmentsTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** The petclinic inputField/selectField pattern, plus a textarea and a forwarding fragment. */
  private static final String FIELDS =
      "<div " + NS + ">"
          + "<t:block t:fragment=\"input(String label, Field name, String type)\">"
          + "<div t:with=\"valid=!fields.hasErrors(name)\" t:class=\"'group' + (valid ? '' : ' has-error')\">"
          + "<label t:for=\"name\" t:text=\"label\">L</label>"
          + "<input t:if=\"type == 'text'\" type=\"text\" t:field=\"name\" t:errorclass=\"bad\" />"
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
            + "<textarea id=\"bio\" name=\"bio\">a &lt;b&gt;bio&lt;/b&gt;</textarea></form>",
        out);
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
        "<form><p><textarea id=\"bio\" name=\"bio\">a &lt;b&gt;bio&lt;/b&gt;</textarea></p></form>",
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
