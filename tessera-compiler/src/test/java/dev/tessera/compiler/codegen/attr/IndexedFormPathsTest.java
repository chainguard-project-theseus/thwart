package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Forms;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Indexed form paths ({@code *{items[i].name}}) in t:field, t:errors, t:errorclass and fields.* (#123). */
class IndexedFormPathsTest {
  // The form object is named "owner" so that FormFixture.TestForms applies.
  private static final String HEAD =
      "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Order owner\" t:object=\"owner\">";

  private static Compilation.Builder model() {
    return Compilation.builder()
        .java("com.example.Item", "package com.example; public record Item(String name, int qty) {}")
        .java(
            "com.example.Order",
            "package com.example; import java.util.*; public record Order(List<Item> items, Item[] array,"
                + " Map<Integer, Item> byId, String note, List<String> tags) {}");
  }

  private static Compilation compile(String body) {
    return model().template("form.html", HEAD + body + "</form>").compile();
  }

  private static String render(Compilation c, Forms forms) {
    Object a = c.newInstance("com.example.Item", "a", 1);
    Object b = c.newInstance("com.example.Item", "b<", 2);
    Object array = java.lang.reflect.Array.newInstance(c.newInstance("com.example.Item", "x", 0).getClass(), 1);
    java.lang.reflect.Array.set(array, 0, a);
    Object order = c.newInstance("com.example.Order", List.of(a, b), array, Map.of(), "n", List.of("t"));
    Map<String, Object> model = new HashMap<>();
    model.put("owner", order);
    return c.render("form", model, ctx(forms));
  }

  @Test
  void loopRendersSpringNamesIdsAndValues() {
    Compilation c =
        compile(
                "<div t:each=\"item : owner.items\"><input t:field=\"*{items[itemStat.index].name}\">"
                    + "<input t:field=\"items[${itemStat.index}].qty\"></div>"
                    + "<t:block t:with=\"i=1\"><textarea t:field=\"*{items[i].name}\"></textarea></t:block>"
                    + "<input t:field=\"*{items[0].name}\"><input t:field=\"*{array[0].name}\">"
                    + "<input t:field=\"*{tags[0]}\">")
            .assertSuccess();
    assertEquals(
        "<form><div><input id=\"items0.name\" name=\"items[0].name\" value=\"a\">"
            + "<input id=\"items0.qty\" name=\"items[0].qty\" value=\"1\"></div>"
            + "<div><input id=\"items1.name\" name=\"items[1].name\" value=\"b&lt;\">"
            + "<input id=\"items1.qty\" name=\"items[1].qty\" value=\"2\"></div>"
            + "<textarea id=\"items1.name\" name=\"items[1].name\">\nb&lt;</textarea>"
            + "<input id=\"items0.name\" name=\"items[0].name\" value=\"a\">"
            + "<input id=\"array0.name\" name=\"array[0].name\" value=\"a\">"
            + "<input id=\"tags0\" name=\"tags[0]\" value=\"t\"></form>",
        render(c, new TestForms()));
  }

  @Test
  void valuesAndErrorsGoThroughFormsWithTheConcretePath() {
    Compilation c =
        compile(
                "<div t:each=\"item : owner.items\"><input t:field=\"*{items[itemStat.index].name}\" t:errorclass=\"bad\">"
                    + "<span t:errors=\"*{items[itemStat.index].name}\">e</span>"
                    + "<b t:if=\"fields.hasErrors(*{items[itemStat.index].name})\">!</b>"
                    + "<i t:text=\"fields.errors(*{items[itemStat.index].name}).size()\">0</i></div>"
                    + "<p t:if=\"fields.hasErrors('items[1].name')\">literal</p>")
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.rejected.put("items[1].name", "\"><x>");
    forms.errors.put("items[1].name", List.of("Name <required>"));
    assertEquals(
        "<form><div><input id=\"items0.name\" name=\"items[0].name\" value=\"a\"><i>0</i></div>"
            + "<div><input id=\"items1.name\" name=\"items[1].name\" value=\"&quot;&gt;&lt;x&gt;\" class=\"bad\">"
            + "<span>Name &lt;required&gt;</span><b>!</b><i>1</i></div><p>literal</p></form>",
        render(c, forms));
  }

  @Test
  void fieldFragmentParameterTakesALiteralIndexedPath() {
    Compilation c =
        model()
            .template(
                "fields.html",
                "<div xmlns:t=\"https://tessera.dev\"><t:block t:fragment=\"area(Field name)\">"
                    + "<textarea t:field=\"name\"></textarea><span t:errors=\"name\">e</span></t:block></div>")
            .template("form.html", HEAD + "<t:block t:replace=\"~{fields :: area('items[1].name')}\"></t:block></form>")
            .compile()
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.errors.put("items[1].name", List.of("bad"));
    assertEquals(
        "<form><textarea id=\"items1.name\" name=\"items[1].name\">\nb&lt;</textarea><span>bad</span></form>",
        render(c, forms));
  }

  @Test
  void indexMustBeAnIntLiteralOrIntVariable() {
    compile("<input t:field=\"*{items[owner.note].name}\">")
        .assertError("form.html:1:", "a field path index must be an int, got java.lang.String");
    compile("<input t:field=\"*{items[1L].name}\">").assertError("a field path index must be an int, got long");
    compile("<div t:each=\"item : owner.items\"><input t:field=\"*{items[itemStat.index + 1].name}\"></div>")
        .assertError("form.html:1:", "a field path index must be an int literal or an int variable");
    compile("<input t:field=\"*{items['0'].name}\">").assertError("a field path index must be an int, got java.lang.String");
    compile("<b t:if=\"fields.hasErrors(*{items[owner.note].name})\">x</b>")
        .assertError("a field path index must be an int, got java.lang.String");
    compile("<b t:if=\"fields.hasErrors('items[x].name')\">x</b>").assertError("'items[x].name' is not a field path");
    compile("<b t:if=\"fields.hasErrors('items[01].name')\">x</b>").assertError("'items[01].name' is not a field path");
    compile("<b t:if=\"fields.hasErrors('items[0]].name')\">x</b>").assertError("'items[0]].name' is not a field path");
  }

  @Test
  void onlyListsAndArraysCanBeIndexed() {
    compile("<input t:field=\"*{note[0]}\">")
        .assertError("form.html:1:", "cannot index java.lang.String in a field path: [...] works on a List or an array");
    compile("<input t:field=\"*{byId[0].name}\">").assertError("in a field path: [...] works on a List or an array");
    compile("<span t:errors=\"*{items[0].name[0]}\">e</span>").assertError("cannot index java.lang.String");
  }

  @Test
  void propertyMustExistOnTheElementType() {
    compile("<input t:field=\"*{items[0].nope}\">")
        .assertError("form.html:1:", "'nope' is not a property of com.example.Item");
    compile("<b t:if=\"fields.hasErrors('items[0].nope')\">x</b>")
        .assertError("'nope' is not a property of com.example.Item");
  }

  @Test
  void errorClassStaysQuietWhenFieldReports() {
    Compilation c = compile("<input t:field=\"*{items[owner.note].name}\" t:errorclass=\"bad\">");
    assertEquals(1, c.errors().size(), c.errors().toString());
  }
}
