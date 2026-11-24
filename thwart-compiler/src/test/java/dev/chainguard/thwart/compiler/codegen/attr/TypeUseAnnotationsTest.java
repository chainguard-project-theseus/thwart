package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.chainguard.thwart.compiler.codegen.attr.FormFixture.TestForms;
import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Types written into generated source leave out type-use annotations (#627). JDK 8's javac renders
 * {@code List<@NotNull Integer>}'s element as {@code (@NotNull :: java.lang.Integer)}, which does
 * not compile; {@code Jdk8ProcessorTest} compiles the same shapes with a real JDK 8 javac. Here, at
 * the release-8 floor, the generated source must name the types without the annotations.
 */
class TypeUseAnnotationsTest {
  private static final String ANNOTATIONS =
      "package com.example; import java.lang.annotation.*; public final class Constraints {"
          + " @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) public @interface NotNull {}"
          + " @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) public @interface Min { long value(); } }";

  /** A Java 8 bean (no records at the floor) with annotated element, nested and array types. */
  private static final String FORM =
      "package com.example; import java.util.*; import com.example.Constraints.*;"
          + " @dev.chainguard.thwart.runtime.TemplateView public class CartForm {"
          + " private final List<@NotNull @Min(0) Integer> quantities;"
          + " private final Map<String, List<@NotNull Integer>> byKey;"
          + " private final @NotNull String @NotNull [] names;"
          + " public CartForm(List<@NotNull @Min(0) Integer> quantities, Map<String, List<@NotNull Integer>> byKey,"
          + " String[] names) { this.quantities = quantities; this.byKey = byKey; this.names = names; }"
          + " public List<@NotNull @Min(0) Integer> getQuantities() { return quantities; }"
          + " public Map<String, List<@NotNull Integer>> getByKey() { return byKey; }"
          + " public @NotNull String @NotNull [] getNames() { return names; }"
          + " public @NotNull Integer getFirst() { return quantities.get(0); } }";

  private static final String HEAD =
      "<form xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"com.example.*\" t:params=\"CartForm owner\" t:object=\"owner\">";

  private static Compilation compile(String body) {
    Compilation c =
        Compilation.builder()
            .release(8)
            .java("com.example.Constraints", ANNOTATIONS)
            .java("com.example.CartForm", FORM)
            .template("form.html", HEAD + body + "</form>")
            .compile()
            .assertSuccess();
    String source = c.generatedSource("thwart.generated.FormTemplate");
    assertFalse(source.contains("@com.example"), source);
    return c;
  }

  private static String render(Compilation c) {
    Map<String, List<Integer>> byKey = new HashMap<>();
    byKey.put("k", List.of(7));
    Object form = c.newInstance("com.example.CartForm", List.of(3, 4), byKey, new String[] {"x", "y"});
    Map<String, Object> model = new HashMap<>();
    model.put("owner", form);
    return c.render("form", model, ctx(new TestForms()));
  }

  @Test
  void fieldOverTypeUseAnnotatedElements() {
    Compilation c =
        compile(
            "<div t:each=\"q : owner.quantities\"><input t:field=\"*{quantities[qStat.index]}\"></div>"
                + "<input t:field=\"*{byKey['k'][0]}\"><input t:field=\"*{names[1]}\">");
    assertEquals(
        "<form><div><input id=\"quantities0\" name=\"quantities[0]\" value=\"3\"></div>"
            + "<div><input id=\"quantities1\" name=\"quantities[1]\" value=\"4\"></div>"
            + "<input id=\"byKey&#39;k&#39;0\" name=\"byKey[&#39;k&#39;][0]\" value=\"7\">"
            + "<input id=\"names1\" name=\"names[1]\" value=\"y\"></form>",
        render(c));
  }

  @Test
  void nullSafeNavigationLocalsLoopsAndCastsOverTypeUseAnnotatedTypes() {
    Compilation c =
        compile(
            "<p t:text=\"${owner?.first}\">q</p><p t:text=\"${owner?.names[0]}\">n</p>"
                + "<t:block t:with=\"qs=${owner.quantities}, ns=${owner.names}, m=${owner.byKey}\">"
                + "<i t:each=\"q : ${qs}\" t:text=\"${q}\">q</i><b t:each=\"n : ${ns}\" t:text=\"${n}\">n</b>"
                + "<u t:each=\"e : ${m}\" t:text=\"${e.value[0]}\">e</u>"
                + "<s t:text=\"${true ? qs[0] : owner.first}\">c</s></t:block>");
    assertEquals(
        "<form><p>3</p><p>x</p><i>3</i><i>4</i><b>x</b><b>y</b><u>7</u><s>3</s></form>", render(c));
  }

  @Test
  void elvisOverTypeUseAnnotatedOperands() {
    Compilation c =
        compile(
            "<p t:text=\"${owner.names[0] ?: 'none'}\">n</p><p t:text=\"${owner.quantities[0] ?: owner.first}\">q</p>"
                + "<p t:text=\"${owner.first ?: null}\">f</p>");
    assertEquals("<form><p>x</p><p>3</p><p>3</p></form>", render(c));
  }
}
