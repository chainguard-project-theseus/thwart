package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static dev.tessera.compiler.codegen.attr.FormFixture.owner;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code fields.value(path)}, a bound field's display value, and {@code fields.errors(path).first},
 * its first error (#133): JSP {@code spring:bind}'s {@code status.value} and
 * {@code status.errorMessage}, readable inside a {@code Field} fragment.
 */
class FieldValueTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** The petclinic inputField "valid" icon, from the field alone. */
  private static final String FIELDS =
      "<div " + NS + "><t:block t:fragment=\"input(Field name)\">"
          + "<input type=\"text\" t:field=\"name\" />"
          + "<i t:if=\"${!fields.hasErrors(name) and !fields.value(name).isEmpty}\">ok</i>"
          + "<b t:text=\"${fields.value(name)}\">v</b>"
          + "<em t:if=\"${fields.hasErrors(name)}\" t:text=\"${fields.errors(name).first}\">e</em>"
          + "</t:block></div>";

  private static final String HEAD =
      "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"owner\">";

  private static Compilation compile(String body) {
    return FormFixture.model()
        .template("fragments/fields.html", FIELDS)
        .template("form.html", HEAD + body + "</form>")
        .compile();
  }

  private static String render(Compilation c, Object owner, TestForms forms) {
    return c.render("form", Map.of("owner", owner), ctx(forms));
  }

  @Test
  void fieldFragmentReadsValueAndFirstError() {
    Compilation c =
        compile(
                "<t:block t:replace=\"~{fragments/fields :: input('firstName')}\"></t:block>"
                    + "<t:block t:replace=\"~{fragments/fields :: input('address.city')}\"></t:block>")
            .assertSuccess();
    assertEquals(
        "<form><input type=\"text\" id=\"firstName\" name=\"firstName\" value=\"Ann\" /><i>ok</i><b>Ann</b>"
            + "<input type=\"text\" id=\"address.city\" name=\"address.city\" value=\"\" /><b></b></form>",
        render(c, owner(c, "Ann", null), new TestForms()));

    // After a failed bind: the rejected value (escaped, it is a String) and the first error.
    TestForms forms = new TestForms();
    forms.rejected.put("firstName", "<script>");
    forms.errors.put("firstName", List.of("is <bad>", "second"));
    assertEquals(
        "<form><input type=\"text\" id=\"firstName\" name=\"firstName\" value=\"&lt;script&gt;\" />"
            + "<b>&lt;script&gt;</b><em>is &lt;bad&gt;</em>"
            + "<input type=\"text\" id=\"address.city\" name=\"address.city\" value=\"Madison\" /><i>ok</i>"
            + "<b>Madison</b></form>",
        render(c, owner(c, "Ann", "Madison"), forms));
  }

  @Test
  void valueOfAPathInsideTheForm() {
    Compilation c =
        compile(
                "<p t:text=\"fields.value('age')\"></p><p t:text=\"${fields.value(*{address.city})}\"></p>"
                    + "<p t:title=\"fields.value('bio')\"></p><p t:text=\"fields.errors('age').first\"></p>")
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.errors.put("age", List.of("too old"));
    assertEquals(
        "<form><p>42</p><p>Madison</p><p title=\"a &lt;b&gt;bio&lt;/b&gt;\"></p><p>too old</p></form>",
        render(c, owner(c, "Ann", "Madison"), forms));
  }

  @Test
  void valueNeedsAFieldPath() {
    compile("<p t:text=\"fields.value('*')\"></p>")
        .assertError("form.html:1:", "fields.value takes a field path such as 'lastName', not '*'");
    compile("<p t:text=\"fields.value('global')\"></p>").assertError("fields.value takes a field path", "not 'global'");
    compile("<p t:text=\"fields.value('nope')\"></p>").assertError("'nope' is not a property of com.example.Owner");
    compile("<p t:text=\"fields.value(owner.firstName)\"></p>").assertError("fields.value takes one string literal");
    compile("<p t:text=\"fields.value()\"></p>").assertError("fields.value takes one string literal");
    compile("<p t:text=\"fields.valu('age')\"></p>").assertError("unknown function fields.valu", "value('path')");
    FormFixture.model()
        .template("form.html", "<p " + NS + " t:text=\"fields.value('age')\"></p>")
        .compile()
        .assertError("fields.value(...) can only be used inside t:object");
  }

  @Test
  void valueIsAStringSoRawOutputStillNeedsASafeType() {
    compile("<p t:utext=\"fields.value('bio')\"></p>").assertError("t:utext");
  }
}
