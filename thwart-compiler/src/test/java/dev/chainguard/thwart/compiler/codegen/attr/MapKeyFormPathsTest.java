package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.codegen.attr.FormFixture.TestForms;
import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.Forms;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Map key segments in form paths ({@code *{prices[key]}}, {@code *{prices['x']}}) in t:field,
 * t:errors and fields.* (#190): Spring's unquoted {@code prices[x]} name and
 * Thymeleaf's bracket-free id {@code pricesx}.
 */
class MapKeyFormPathsTest {
  // The form object is named "owner" so that FormFixture.TestForms applies.
  private static final String HEAD =
      "<form xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"com.example.*\" t:params=\"Quote owner\" t:object=\"owner\">";

  private static Compilation.Builder model() {
    return Compilation.builder()
        .java("com.example.Item", "package com.example; public record Item(String name) {}")
        .java("com.example.Color", "package com.example; public enum Color { RED, GREEN }")
        .java(
            "com.example.Quote",
            "package com.example; import java.util.*; public record Quote(Map<String, String> prices,"
                + " Map<Long, Item> byId, Map<Color, String> colors, Map<String, Thread> threads,"
                + " Map<Double, String> rates, String note, List<Item> items) {}");
  }

  private static Compilation compile(String body) {
    return model().template("form.html", HEAD + body + "</form>").compile();
  }

  private static String render(Compilation c, Forms forms, Map<String, String> prices) {
    Map<Long, Object> byId = new LinkedHashMap<>();
    byId.put(7L, c.newInstance("com.example.Item", "seven"));
    Map<Object, String> colors = new LinkedHashMap<>();
    colors.put(c.enumConstant("com.example.Color", "GREEN"), "g");
    Object quote = c.newInstance("com.example.Quote", prices, byId, colors, Map.of(), Map.of(), "n", List.of());
    Map<String, Object> model = new HashMap<>();
    model.put("owner", quote);
    return c.render("form", model, ctx(forms));
  }

  private static Map<String, String> prices(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void rendersSpringNamesAndBracketFreeIds() {
    Compilation c =
        compile(
                "<div t:each=\"e : owner.prices\"><input t:field=\"*{prices[e.key]}\"></div>"
                    + "<input t:field=\"*{prices['x']}\"><input t:field=\"prices[${'x'}]\">"
                    + "<t:block t:each=\"e : owner.byId\"><input t:field=\"*{byId[e.key].name}\"></t:block>"
                    + "<t:block t:each=\"e : owner.colors\"><input t:field=\"*{colors[e.key]}\"></t:block>")
            .assertSuccess();
    assertEquals(
        "<form><div><input id=\"pricesx\" name=\"prices[x]\" value=\"1\"></div>"
            + "<div><input id=\"pricesa.b&lt;&amp;\" name=\"prices[a.b&lt;&amp;]\" value=\"2\"></div>"
            + "<input id=\"pricesx\" name=\"prices[x]\" value=\"1\"><input id=\"pricesx\" name=\"prices[x]\" value=\"1\">"
            + "<input id=\"byId7.name\" name=\"byId[7].name\" value=\"seven\">"
            + "<input id=\"colorsGREEN\" name=\"colors[GREEN]\" value=\"g\"></form>",
        render(c, new TestForms(), prices("x", "1", "a.b<&", "2")));
  }

  @Test
  void valuesAndErrorsGoThroughFormsWithTheConcretePath() {
    Compilation c =
        compile(
                "<div t:each=\"e : owner.prices\"><input t:field=\"*{prices[e.key]}\" t:classappend=\"fields.hasErrors(*{prices[e.key]}) ? 'bad'\">"
                    + "<span t:errors=\"*{prices[e.key]}\">e</span>"
                    + "<b t:if=\"fields.hasErrors(*{prices[e.key]})\">!</b>"
                    + "<i t:text=\"fields.value(*{prices[e.key]})\">v</i></div>"
                    + "<p t:if=\"fields.hasErrors('prices[y]')\">literal</p>"
                    // Only the path is read, so the value type need not be view-safe (#129).
                    + "<span t:errors=\"*{threads['t']}\">e</span>")
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.rejected.put("prices[y]", "\"><x>");
    forms.errors.put("prices[y]", List.of("Price <required>"));
    forms.errors.put("threads[t]", List.of("T"));
    assertEquals(
        "<form><div><input id=\"pricesx\" name=\"prices[x]\" value=\"1\"><i>1</i></div>"
            + "<div><input id=\"pricesy\" name=\"prices[y]\" value=\"&quot;&gt;&lt;x&gt;\" class=\"bad\">"
            + "<span>Price &lt;required&gt;</span><b>!</b><i>&quot;&gt;&lt;x&gt;</i></div><p>literal</p>"
            + "<span>T</span></form>",
        render(c, forms, prices("x", "1", "y", "2")));
  }

  @Test
  void fieldFragmentParameterTakesALiteralMapKeyPath() {
    Compilation c =
        model()
            .template(
                "fields.html",
                "<div xmlns:t=\"https://thwart.chainguard.dev\"><t:block t:fragment=\"in(Field name)\">"
                    + "<input t:field=\"name\"><span t:errors=\"name\">e</span></t:block></div>")
            .template("form.html", HEAD + "<t:block t:replace=\"~{fields :: in('prices[x]')}\"></t:block></form>")
            .compile()
            .assertSuccess();
    TestForms forms = new TestForms();
    forms.errors.put("prices[x]", List.of("bad"));
    assertEquals(
        "<form><input id=\"pricesx\" name=\"prices[x]\" value=\"1\"><span>bad</span></form>",
        render(c, forms, prices("x", "1")));
  }

  @Test
  void aKeyThatWouldChangeThePathFailsTheRender() {
    Compilation c = compile("<div t:each=\"e : owner.prices\"><input t:field=\"*{prices[e.key]}\"></div>").assertSuccess();
    for (String key : new String[] {"a]b", "a[b", "'q'", "\"q\"", ""}) {
      Throwable t = assertThrows(Throwable.class, () -> render(c, new TestForms(), prices(key, "1")), key);
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t instanceof IllegalArgumentException && t.getMessage().contains("cannot be bound"), t.toString());
    }
  }

  @Test
  void onlyMapsListsAndArraysCanBeIndexed() {
    compile("<input t:field=\"*{note['k']}\">")
        .assertError("form.html:1:", "cannot index java.lang.String in a field path: [...] works on a List, an array or a Map");
    compile("<span t:errors=\"*{note['k']}\">e</span>").assertError("cannot index java.lang.String");
  }

  @Test
  void valueTypeMustBeViewSafeWhereTheValueIsRead() {
    compile("<input t:field=\"*{threads['t']}\">").assertError("form.html:1:", "java.lang.Thread is never view-safe");
    compile("<i t:text=\"fields.value(*{threads['t']})\">v</i>")
        .assertError("form.html:1:", "java.lang.Thread is never view-safe");
  }

  @Test
  void propertyMustExistOnTheValueType() {
    compile("<t:block t:each=\"e : owner.byId\"><input t:field=\"*{byId[e.key].nope}\"></t:block>")
        .assertError("form.html:1:", "'nope' is not a property of com.example.Item");
    compile("<b t:if=\"fields.hasErrors('prices[x].nope')\">x</b>").assertError("'nope' is not a property of");
  }

  @Test
  void keysMustBeSafeLiteralsOrVariablesOfTheKeyType() {
    compile("<input t:field=\"*{prices[1]}\">").assertError("form.html:1:", "map key must be a java.lang.String, got int");
    compile("<input t:field=\"*{prices['a]b']}\">").assertError("form.html:1:", "map key 'a]b' cannot be bound");
    compile("<input t:field=\"*{prices['']}\">").assertError("map key '' cannot be bound");
    compile("<input t:field=\"*{prices[owner.note + 'x']}\">")
        .assertError("form.html:1:", "a map key in a field path must be a literal or a variable");
    compile("<input t:field=\"*{prices[true]}\">")
        .assertError("a map key in a field path must be an int, a long, a String or an enum, got boolean");
    compile("<t:block t:each=\"e : owner.rates\"><input t:field=\"*{rates[e.key]}\"></t:block>")
        .assertError("a map key in a field path must be an int, a long, a String or an enum, got java.lang.Double");
    compile("<b t:if=\"fields.hasErrors('prices[a b]')\">x</b>").assertError("'prices[a b]' is not a field path");
    // A List index is still an int.
    compile("<input t:field=\"*{items['0'].name}\">").assertError("a field path index must be an int, got java.lang.String");
  }
}
