package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Fragment;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #676: {@code Fragment} is compile-time only. No Java code can build or vouch for one (#581), no
 * page or function takes one, and a fragment with a {@code Fragment} parameter has no Java-facing
 * method at all (#600): fragment arguments exist only between compiled templates, which the
 * verifier checks at the call site.
 */
class FragmentFromJavaTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  private static Compilation java(String body) {
    return Compilation.builder()
        .java("com.example.J", "package com.example; import dev.tessera.runtime.*; public final class J { " + body + " }")
        .compile();
  }

  /** Negative: Java can neither implement, extend nor construct a Fragment. */
  @Test
  void javaCannotBuildAFragment() {
    java("static final Fragment F = (ctx, out) -> out.write(\"</form><form action=x>\");").assertError("not a functional interface");
    java("static final class S extends Fragment {}").assertError("cannot inherit from final");
    java("static final Fragment F = new Fragment();").assertError("Fragment()", "private access");
  }

  /** Negative: a page can't take a Fragment, since nothing could pass one (file:line:col error). */
  @Test
  void pageFragmentParameterIsRejected() {
    Compilation.builder()
        .template("sink.html", "<div " + NS + " t:params=\"String s, Fragment p\">\n<form action=\"https://other.example/search\">"
            + "<t:block t:insert=\"${p}\"/></form></div>")
        .compile()
        .assertError("sink.html:1:", "parameter 'p' has type Fragment", "t:fragment signature");
  }

  /**
   * A template-built fragment argument smuggled out through an {@code Object} function argument is
   * a {@code Fragment.Body}, not a {@code Fragment}, and no generated public method takes either, so
   * Java has nowhere to hand it back to a template.
   */
  @Test
  void smuggledFragmentArgumentHasNoWayBack() throws Exception {
    Compilation c = Compilation.builder()
        .java("com.example.Stash", "package com.example;\nimport dev.tessera.runtime.*;\n@TemplateFunctions(\"stash\")\n"
            + "public final class Stash {\n  private Stash() {}\n  public static Object last;\n"
            + "  @TemplateFunction public static String put(Object o) { last = o; return \"\"; }\n}\n")
        .template("f.html", "<div " + NS + ">\n<div t:fragment=\"leak(Fragment b)\"><span t:text=\"${#stash.put(b)}\"></span>"
            + "<t:block t:insert=\"${b}\"/></div></div>")
        .template("page.html", "<div " + NS + ">\n<t:block t:replace=\"~{f :: leak(~{::i})}\"></t:block><i>x</i></div>")
        .compile()
        .assertSuccess();
    assertEquals("<div>\n<div><span></span><i>x</i></div><i>x</i></div>", c.render("page", Map.of()));
    Object smuggled = c.classLoader().loadClass("com.example.Stash").getField("last").get(null);
    assertTrue(smuggled instanceof Fragment.Body, String.valueOf(smuggled));
    assertFalse(Fragment.class.isInstance(smuggled));
    for (String name : new String[] {"FTemplate", "PageTemplate"}) {
      assertNoPublicFragmentParameter(c, name);
    }
  }

  private static void assertNoPublicFragmentParameter(Compilation c, String name) throws Exception {
    for (Method m : c.classLoader().loadClass("tessera.generated." + name).getMethods()) {
      for (Class<?> p : m.getParameterTypes()) {
        assertTrue(p != Fragment.class && p != Fragment.Body.class, name + "." + m.getName());
      }
    }
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
            + "null, RenderContext.defaults(), out);")
        .assertError("cannot find symbol", "fragment_x");
    fragmentMethods("tessera.generated.FTemplate.fragment_uncalled("
            + "null, RenderContext.defaults(), out);")
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
   * Negative: no generated class has a public method that takes a {@code Fragment} or its Java
   * type {@code Fragment.Body}. A
   * fragment with a {@code Fragment} parameter is a private method of its own class and of each
   * class that calls it, so only generated code passes it what the verifier checked where it lands.
   */
  @Test
  void noPublicMethodTakesAFragment() throws Exception {
    Compilation c = fragmentMethods(null).assertSuccess();
    for (String name : new String[] {"FTemplate", "PageTemplate", "DTemplate"}) {
      assertNoPublicFragmentParameter(c, name);
    }
    assertTrue(c.generatedSource("tessera.generated.PageTemplate").contains("private static void fragment_x$0("));
    assertEquals("<div>\n<p><span><em>e</em></span></p><em>e</em></div>", c.render("page", Map.of()));
    fragmentMethods("tessera.generated.PageTemplate.fragment_x$0("
            + "null, RenderContext.defaults(), out);")
        .assertError("fragment_x$0", "private access");
  }
}
