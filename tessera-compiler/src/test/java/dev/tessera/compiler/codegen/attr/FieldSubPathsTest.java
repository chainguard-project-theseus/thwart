package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sub-paths of a {@code Field<T>} fragment parameter (#239): one fragment binds {@code c.answer}
 * and {@code c.description} of whichever field the caller passes, as JSP tag files do with
 * {@code <form:input path="${field}.answer"/>}.
 */
class FieldSubPathsTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static final String FIELDS =
      "<div " + NS + " t:import=\"com.example.Criterion\">"
          + "<div t:fragment=\"criterion(Field<Criterion> c)\" t:classappend=\"fields.hasErrors(c.answer) ? 'bad'\">"
          + "<input type=\"checkbox\" t:field=\"c.answer\" />"
          + "<input type=\"text\" t:field=\"c.description\" t:errorclass=\"err\" />"
          + "<span t:errors=\"c.description\">E</span>"
          + "<t:block t:replace=\"~{:: area(c.description)}\"></t:block></div>"
          + "<textarea t:fragment=\"area(Field<String> f)\" t:field=\"f\"></textarea>"
          + "</div>";

  // The form object is named "owner" so that FormFixture.TestForms applies.
  private static final String HEAD =
      "<form " + NS + " t:import=\"com.example.*\" t:params=\"Espd owner\" t:object=\"owner\">";

  private static Compilation.Builder model() {
    return Compilation.builder()
        .java("com.example.Criterion", "package com.example; public record Criterion(boolean answer, String description) {}")
        .java(
            "com.example.Espd",
            "package com.example; import java.util.*; public record Espd(Criterion exclusion, List<Criterion> criteria,"
                + " String name) {}");
  }

  private static Compilation compile(String fields, String body) {
    return model().template("frag.html", fields).template("form.html", HEAD + body + "</form>").compile();
  }

  private static Object espd(Compilation c) {
    Object exclusion = c.newInstance("com.example.Criterion", true, "x<y");
    Object first = c.newInstance("com.example.Criterion", false, "one");
    Object second = c.newInstance("com.example.Criterion", false, "two");
    return c.newInstance("com.example.Espd", exclusion, List.of(first, second), "n");
  }

  @Test
  void oneFragmentBindsSubPathsOfDifferentFields() {
    Compilation c =
        compile(
                FIELDS,
                "<div t:replace=\"~{frag :: criterion('exclusion')}\"></div>"
                    + "<div t:replace=\"~{frag :: criterion('criteria[1]')}\"></div>")
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.errors.put("criteria[1].description", List.of("too <short>"));
    forms.errors.put("criteria[1].answer", List.of("required"));
    forms.rejected.put("criteria[1].description", "\"><b>");
    assertEquals(
        "<form><div><input type=\"checkbox\" id=\"exclusion.answer\" name=\"exclusion.answer\" value=\"true\""
            + " checked=\"checked\" /><input type=\"hidden\" name=\"_exclusion.answer\" value=\"on\" />"
            + "<input type=\"text\" id=\"exclusion.description\" name=\"exclusion.description\" value=\"x&lt;y\" />"
            + "<textarea id=\"exclusion.description\" name=\"exclusion.description\">\nx&lt;y</textarea></div>"
            + "<div class=\"bad\"><input type=\"checkbox\" id=\"criteria1.answer\" name=\"criteria[1].answer\" value=\"true\" />"
            + "<input type=\"hidden\" name=\"_criteria[1].answer\" value=\"on\" />"
            + "<input type=\"text\" id=\"criteria1.description\" name=\"criteria[1].description\""
            + " value=\"&quot;&gt;&lt;b&gt;\" class=\"err\" /><span>too &lt;short&gt;</span>"
            + "<textarea id=\"criteria1.description\" name=\"criteria[1].description\">\n&quot;&gt;&lt;b&gt;</textarea></div></form>",
        c.render("form", Map.of("owner", espd(c)), ctx(forms)));
  }

  @Test
  void indexedSubPathOfAListField() {
    String fields =
        "<div " + NS + " t:import=\"com.example.Criterion\"><p t:fragment=\"f(Field<List<Criterion>> cs, int i)\">"
            + "<input t:field=\"cs[i].description\" /></p></div>";
    Compilation c = compile(fields, "<p t:replace=\"~{frag :: f('criteria', 1)}\"></p>").assertSuccess();
    assertEquals(
        "<form><p><input id=\"criteria1.description\" name=\"criteria[1].description\" value=\"two\" /></p></form>",
        c.render("form", Map.of("owner", espd(c)), ctx(new TestForms())));
  }

  @Test
  void typedFieldParameterChecksTheArgumentType() {
    compile(FIELDS, "\n<div t:replace=\"~{frag :: criterion('name')}\"></div>")
        .assertError("form.html:2:", "has type dev.tessera.runtime.Field<com.example.Criterion>, but the field has type java.lang.String");
    compile(FIELDS, "<div t:replace=\"~{frag :: area('exclusion')}\"></div>")
        .assertError("but the field has type com.example.Criterion");
    compile(FIELDS, "<div t:replace=\"~{frag :: criterion('criteria')}\"></div>")
        .assertError("but the field has type java.util.List<com.example.Criterion>");
  }

  // ---- negative tests ----

  @Test
  void unknownSubPropertyIsBuildErrorInTheFragment() {
    String fields =
        "<div " + NS + " t:import=\"com.example.Criterion\">\n"
            + "<p t:fragment=\"f(Field<Criterion> c)\">\n  <input t:field=\"c.answr\" /></p></div>";
    compile(fields, "").assertError("frag.html:3:", "'answr' is not a property of com.example.Criterion");
    compile(fields.replace("t:field=\"c.answr\"", "t:errors=\"c.description.nope\""), "")
        .assertError("frag.html:3:", "'nope'");
    compile(fields.replace("<input t:field=\"c.answr\" />", "<b t:if=\"fields.hasErrors(c.nope)\">x</b>"), "")
        .assertError("frag.html:3:", "'nope' is not a property of com.example.Criterion");
  }

  @Test
  void plainFieldHasNoSubPaths() {
    compile("<div " + NS + "><p t:fragment=\"f(Field c)\"><input t:field=\"c.answer\" /></p></div>", "")
        .assertError("frag.html:1:", "'c' is a Field of unknown type", "Field<Criterion> c");
  }

  @Test
  void plainFieldCannotBePassedAsTypedField() {
    String fields =
        "<div " + NS + " t:import=\"com.example.Criterion\">"
            + "<p t:fragment=\"f(Field c)\"><t:block t:replace=\"~{:: g(c)}\"></t:block></p>"
            + "<p t:fragment=\"g(Field<Criterion> c)\"><input t:field=\"c.description\" /></p></div>";
    compile(fields, "").assertError("but 'c' is a dev.tessera.runtime.Field<?>");
  }

  @Test
  void noStringReachesTheBindPath() {
    String head =
        "<div " + NS + " t:import=\"com.example.Criterion\">"
            + "<p t:fragment=\"f(Field<Criterion> c, Field<List<Criterion>> cs, String s, int i)\">";
    String tail = "</p><p t:fragment=\"g(Field<String> f)\"></p></div>";
    compile(head + "<input t:field=\"c + '.answer'\" />" + tail, "")
        .assertError("t:field can only be used inside t:object");
    compile(head + "<input t:field=\"cs[s].answer\" />" + tail, "").assertError("a field path index must be an int");
    compile(head + "<input t:field=\"cs[i + 1].answer\" />" + tail, "").assertError("a field path index must be an int literal");
    compile(head + "<input t:field=\"c[i].answer\" />" + tail, "").assertError("cannot index com.example.Criterion");
    compile(head + "<input t:field=\"c?.answer\" />" + tail, "").assertError("t:field can only be used inside t:object");
    compile(head + "<b t:if=\"fields.hasErrors(c + '.answer')\">x</b>" + tail, "")
        .assertError("fields.hasErrors(...) can only be used inside t:object");
    compile(head + "<t:block t:replace=\"~{:: g(c + '.description')}\"></t:block>" + tail, "")
        .assertError("has type Field: pass a field path as a string literal");
    compile(head + "<t:block t:replace=\"~{:: g(s)}\"></t:block>" + tail, "")
        .assertError("has type Field: pass a field path as a string literal");
    // The value is not readable either: a sub-path is a field, not an expression.
    compile(head + "<b t:text=\"c.description\">x</b>" + tail, "").assertError("frag.html:1:");
  }

  @Test
  void fieldTypeMustBeViewSafe() {
    compile("<div " + NS + "><p t:fragment=\"f(Field<StringBuilder> c)\"></p></div>", "")
        .assertError("frag.html:1:", "java.lang.StringBuilder is not view-safe");
  }
}
