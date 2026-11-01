package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.Template;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #581: a {@code Fragment} built in Java is a {@code Fragment.of} of a verified FLOW partial. It
 * can't be a lambda or a subclass, and where Java hands a template a {@code Fragment} (a page's
 * {@code t:params}, a fragment method called from Java) a value without the mark of
 * {@code Fragment.of} is refused at render time, also a template-built one smuggled out.
 */
class FragmentFromJavaTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** Negative: the probes of the issue, a hand-written lambda, subclass or implementation, no longer compile. */
  @Test
  void handWrittenFragmentDoesNotCompile() {
    Compilation.builder()
        .java("com.example.L", "package com.example; public final class L {"
            + " public static final dev.tessera.runtime.Fragment F = (ctx, out) -> out.write(\"</form><form action=x>\"); }")
        .compile()
        .assertError("not a functional interface");
    Compilation.builder()
        .java("com.example.S", "package com.example; public final class S extends dev.tessera.runtime.Fragment {}")
        .compile()
        .assertError("cannot inherit from final");
    Compilation.builder()
        .java("com.example.I", "package com.example; public final class I implements dev.tessera.runtime.Fragment {}")
        .compile()
        .assertError("interface expected here");
  }

  private static final String STASH =
      """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateFunctions("stash")
      public final class Stash {
        private Stash() {}
        public static Object last;
        @TemplateFunction public static String put(Object o) { last = o; return ""; }
      }
      """;

  /** A page that inserts its {@code Fragment} parameter in a cross-origin GET form. */
  private static final String SINK =
      "<div " + NS + " t:params=\"Fragment p\">\n<form action=\"https://other.example/search\"><input name=\"q\"/>"
          + "<t:block t:insert=\"${p}\"/></form></div>";

  private static Compilation smuggling() {
    return Compilation.builder()
        .java("com.example.Stash", STASH)
        .template("f.html", "<div " + NS + ">\n<div t:fragment=\"leak(Fragment b)\"><span t:text=\"${#stash.put(b)}\"></span></div></div>")
        .template(
            "page.html",
            "<div " + NS + ">\n<t:block t:replace=\"~{f :: leak(~{::form})}\"></t:block>"
                + "<form method=\"post\"><button>Go</button></form></div>")
        .template("sink.html", SINK)
        .template("p.html", "<div " + NS + "><i>p</i></div>")
        .compile()
        .assertSuccess();
  }

  /**
   * Negative, the amendment's probe: a template-built fragment smuggled out through an
   * {@code Object} function argument and passed back in as a page parameter is refused at render
   * time, through the registry and through the generated class.
   */
  @Test
  void smuggledTemplateFragmentIsRefusedAsAPageParameter() throws Exception {
    Compilation c = smuggling();
    c.render("page", Map.of());
    Object smuggled = c.classLoader().loadClass("com.example.Stash").getField("last").get(null);
    assertTrue(smuggled instanceof Fragment, String.valueOf(smuggled));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.render("sink", Map.of("p", smuggled)));
    assertTrue(e.getMessage().contains("'sink'") && e.getMessage().contains("'p'") && e.getMessage().contains("Fragment.of"),
        e.getMessage());
    Method of = c.classLoader().loadClass("tessera.generated.SinkTemplate").getMethod("of", Fragment.class);
    Template t = (Template) of.invoke(null, smuggled);
    assertThrows(IllegalArgumentException.class, () -> t.renderToString(RenderContext.defaults()));
    Method render = c.classLoader().loadClass("tessera.generated.SinkTemplate")
        .getMethod("render", Fragment.class, RenderContext.class, Output.class);
    InvocationTargetException direct = assertThrows(InvocationTargetException.class,
        () -> render.invoke(null, smuggled, RenderContext.defaults(), new StringOutput()));
    assertTrue(direct.getCause() instanceof IllegalArgumentException, String.valueOf(direct.getCause()));
  }

  /** Positive control: the same page renders a {@code Fragment.of} value, and null. */
  @Test
  void fragmentOfAsAPageParameterRenders() {
    Compilation c = smuggling();
    Fragment p = Fragment.of(partial(c, "tessera.generated.PTemplate"));
    assertEquals(
        "<div>\n<form action=\"https://other.example/search\"><input name=\"q\"/><div><i>p</i></div></form></div>",
        c.render("sink", Map.of("p", p)));
    assertEquals(
        "<div>\n<form action=\"https://other.example/search\"><input name=\"q\"/></form></div>",
        c.render("sink", new java.util.HashMap<>()));
  }

  private static Compilation fragmentMethods() {
    return Compilation.builder()
        .template(
            "f.html",
            "<div " + NS + ">\n<section t:fragment=\"card(String title, Fragment body)\"><h2 t:text=\"${title}\">t</h2>"
                + "<t:block t:insert=\"${body}\"/></section>"
                + "<aside t:fragment=\"uncalled(Fragment body)\"><t:block t:insert=\"${body}\"/></aside></div>")
        .template("page.html", "<div " + NS + ">\n<t:block t:replace=\"~{f :: card('T', ~{::p})}\"></t:block><p>x</p></div>")
        .template("p.html", "<div " + NS + "><i>p</i></div>")
        .compile()
        .assertSuccess();
  }

  /**
   * Negative: a fragment method called from Java (here, by reflection) refuses a Fragment without
   * the mark, whether or not a template also calls it; the template's own call is unaffected.
   */
  @Test
  void fragmentMethodCalledFromJavaRefusesAnUnverifiedFragment() throws Exception {
    Compilation c = fragmentMethods();
    assertEquals("<div>\n<section><h2>T</h2><p>x</p></section><p>x</p></div>", c.render("page", Map.of()));
    Class<?> f = c.classLoader().loadClass("tessera.generated.FTemplate");
    Fragment evil = Fragment.generated((ctx, out) -> out.write("</section><form action=\"https://evil\">"));
    Method card = f.getMethod("fragment_card", String.class, Fragment.class, RenderContext.class, Output.class);
    Method uncalled = f.getMethod("fragment_uncalled", Fragment.class, RenderContext.class, Output.class);
    StringOutput out = new StringOutput();
    InvocationTargetException e =
        assertThrows(InvocationTargetException.class, () -> card.invoke(null, "T", evil, RenderContext.defaults(), out));
    assertTrue(e.getCause() instanceof IllegalArgumentException && e.getCause().getMessage().contains("'f :: card'")
        && e.getCause().getMessage().contains("'body'"), String.valueOf(e.getCause()));
    e = assertThrows(InvocationTargetException.class, () -> uncalled.invoke(null, evil, RenderContext.defaults(), out));
    assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
    assertFalse(out.toString().contains("evil"), out.toString());
  }

  /** Positive control: the same methods render a {@code Fragment.of} value called from Java. */
  @Test
  void fragmentMethodCalledFromJavaRendersFragmentOf() throws Exception {
    Compilation c = fragmentMethods();
    Class<?> f = c.classLoader().loadClass("tessera.generated.FTemplate");
    Fragment p = Fragment.of(partial(c, "tessera.generated.PTemplate"));
    StringOutput out = new StringOutput();
    f.getMethod("fragment_card", String.class, Fragment.class, RenderContext.class, Output.class)
        .invoke(null, "T", p, RenderContext.defaults(), out);
    f.getMethod("fragment_uncalled", Fragment.class, RenderContext.class, Output.class)
        .invoke(null, p, RenderContext.defaults(), out);
    assertEquals("<section><h2>T</h2><div><i>p</i></div></section><aside><div><i>p</i></div></aside>", out.toString());
  }

  /**
   * The review points docs/context-model.md §5 and docs/security-model.md list: of the public
   * methods a generated class gives Java that take a {@code Fragment}, only {@code fragment$<name>}
   * skips the mark check, and it renders whatever {@code Fragment.generated} wraps. Negative
   * control: {@code fragment_<name>} refuses the same value. A new unchecked entry point fails
   * here until the docs list it.
   */
  @Test
  void uncheckedFragmentEntryPointsAreTheDocumentedReviewPoints() throws Exception {
    Class<?> f = fragmentMethods().classLoader().loadClass("tessera.generated.FTemplate");
    java.util.Set<String> unchecked = new java.util.TreeSet<>();
    for (Method m : f.getMethods()) {
      if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
          && java.util.Arrays.asList(m.getParameterTypes()).contains(Fragment.class)
          && !m.getName().startsWith("fragment_")) {
        unchecked.add(m.getName());
      }
    }
    assertEquals("[fragment$card, fragment$uncalled]", unchecked.toString());
    Fragment evil = Fragment.generated((ctx, out) -> out.write("</section><form action=\"https://evil\">"));
    StringOutput out = new StringOutput();
    f.getMethod("fragment$card", String.class, Fragment.class, RenderContext.class, Output.class)
        .invoke(null, "T", evil, RenderContext.defaults(), out);
    assertTrue(out.toString().contains("evil"), out.toString());
    InvocationTargetException e = assertThrows(InvocationTargetException.class,
        () -> f.getMethod("fragment_card", String.class, Fragment.class, RenderContext.class, Output.class)
            .invoke(null, "T", evil, RenderContext.defaults(), new StringOutput()));
    assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
  }

  /** The parameterless generated template {@code className}, from its {@code of()}. */
  private static Template partial(Compilation c, String className) {
    try {
      return (Template) c.classLoader().loadClass(className).getMethod("of").invoke(null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
