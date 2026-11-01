package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code t:params}) a value without the mark of {@code Fragment.of} is refused at render time,
 * also a template-built one smuggled out. #600: a fragment with a {@code Fragment} parameter has
 * no Java-facing method at all.
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

  /**
   * The issue's probe: {@code x} inserts its {@code Fragment} parameter inside a {@code <p>}, and a
   * template calls it with an {@code <em>}, so the verifier checked that position only for an
   * {@code <em>}. {@code uncalled} has a {@code Fragment} parameter and no caller, {@code plain} has
   * none.
   */
  private static final String FRAGMENTS =
      "<div " + NS + ">\n<p t:fragment=\"x(Fragment c)\"><span t:insert=\"${c}\"></span></p>"
          + "<aside t:fragment=\"uncalled(Fragment body)\"><t:block t:insert=\"${body}\"/></aside>"
          + "<section t:fragment=\"plain(String title)\"><h2 t:text=\"${title}\">t</h2></section></div>";

  private static final String CALLER_PAGE =
      "<div " + NS + ">\n<t:block t:replace=\"~{f :: x(~{::em})}\"></t:block><em>e</em></div>";

  private static Compilation fragmentMethods(String caller) {
    Compilation.Builder b = Compilation.builder()
        .template("f.html", FRAGMENTS)
        .template("page.html", CALLER_PAGE)
        .template("d.html", "<div " + NS + "><div>d</div></div>");
    if (caller != null) {
      b.java("com.example.Caller", "package com.example;\n"
          + "import dev.tessera.runtime.*;\n"
          + "public final class Caller {\n"
          + "  public static String call() {\n"
          + "    StringOutput out = new StringOutput();\n"
          + "    " + caller + "\n"
          + "    return out.toString();\n"
          + "  }\n"
          + "}\n");
    }
    return b.compile();
  }

  /**
   * Negative (#600): Java code can't call a Java-facing method of a fragment with a {@code Fragment}
   * parameter, called by a template or not, so the issue's {@code <div>} can't reach the {@code <p>}
   * of {@code x} through any method a Java caller is meant to use.
   */
  @Test
  void noJavaFacingMethodForAFragmentWithAFragmentParameter() {
    fragmentMethods("tessera.generated.FTemplate.fragment_x("
            + "Fragment.of(tessera.generated.DTemplate.of()), RenderContext.defaults(), out);")
        .assertError("cannot find symbol", "fragment_x");
    fragmentMethods("tessera.generated.FTemplate.fragment_uncalled("
            + "Fragment.of(tessera.generated.DTemplate.of()), RenderContext.defaults(), out);")
        .assertError("cannot find symbol", "fragment_uncalled");
  }

  /**
   * Positive control: a fragment without a {@code Fragment} parameter keeps its Java-facing method,
   * and the template's own call of {@code x} renders.
   */
  @Test
  void fragmentWithoutAFragmentParameterKeepsItsJavaMethod() throws Exception {
    Compilation c = fragmentMethods("tessera.generated.FTemplate.fragment_plain(\"T\", RenderContext.defaults(), out);")
        .assertSuccess();
    assertEquals("<section><h2>T</h2></section>",
        c.classLoader().loadClass("com.example.Caller").getMethod("call").invoke(null));
    assertEquals("<div>\n<p><span><em>e</em></span></p><em>e</em></div>", c.render("page", Map.of()));
  }

  /**
   * The review points docs/context-model.md §5 and docs/security-model.md list: the only public
   * methods of a generated class that take a {@code Fragment} and are not a page's own
   * {@code of}/{@code render} are the template-internal {@code internalUnverified$<name>} methods,
   * and they render whatever {@code Fragment.unverifiedForGeneratedCode} wraps. A new entry point
   * fails here until the docs list it.
   */
  @Test
  void uncheckedFragmentEntryPointsAreTheDocumentedReviewPoints() throws Exception {
    Class<?> f = fragmentMethods(null).assertSuccess().classLoader().loadClass("tessera.generated.FTemplate");
    java.util.Set<String> takesFragment = new java.util.TreeSet<>();
    for (Method m : f.getMethods()) {
      if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
          && java.util.Arrays.asList(m.getParameterTypes()).contains(Fragment.class)) {
        takesFragment.add(m.getName());
      }
    }
    assertEquals("[internalUnverified$uncalled, internalUnverified$x]", takesFragment.toString());
    Fragment div = Fragment.unverifiedForGeneratedCode((ctx, out) -> out.write("<div>d</div>"));
    StringOutput out = new StringOutput();
    f.getMethod("internalUnverified$x", Fragment.class, RenderContext.class, Output.class)
        .invoke(null, div, RenderContext.defaults(), out);
    assertEquals("<p><span><div>d</div></span></p>", out.toString());
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
