package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #561: every {@code Fragment}-typed expression in {@code t:insert}/{@code t:replace}, and every
 * {@code Fragment} passed on as a fragment argument, resolves to a closed set of candidate bindings
 * (a Fragment parameter, {@code null}, a {@code t:with} alias of one, a ternary or Elvis of those),
 * and the verifier parses each candidate where it lands. Anything else is a build error, also any
 * function or method call (the design amendment on #561, #582).
 */
class FragmentCandidatesTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String PARSE_ERROR = "[verifier:PARSE_ERROR]";
  private static final String UNTRACED = "can't trace to the fragment arguments it may be";

  /** The insertions of the design comment, each with the t:with it needs on the element around it. */
  private static final List<String[]> FORMS =
      List.of(
          new String[] {"", "${body}"},
          new String[] {"", "${flag ? body : null}"},
          new String[] {"", "${flag ? body}"},
          new String[] {"", "${!flag ? null : body}"},
          new String[] {"", "${body ?: null}"},
          new String[] {" t:with=\"b=${body}\"", "${b}"},
          new String[] {" t:with=\"b=${flag ? body : null}\"", "${b ?: null}"});

  /** A fragment that inserts {@code body} in a GET form to another origin. */
  private static String crossOriginSlot(String with, String insert) {
    return "<div " + NS + ">\n<div t:fragment=\"slot(Fragment body, boolean flag)\"><form"
        + " action=\"https://other.example/search\"" + with + "><input name=\"q\"/><t:block t:insert=\"" + insert
        + "\"/></form></div></div>";
  }

  private static Compilation slotPage(String with, String insert, String arg, String tail) {
    return Compilation.builder()
        .template("f.html", crossOriginSlot(with, insert))
        .template("page.html", "<div " + NS + ">\n<t:block t:replace=\"~{f :: slot(" + arg + ", true)}\"></t:block>" + tail + "</div>")
        .compile();
  }

  /**
   * The confirmed probe: a POST form passed as a Fragment and inserted in a cross-origin GET form
   * through a ternary, Elvis or t:with alias. A browser drops the inner form tag, so its CSRF token
   * would go to the other origin in the query string.
   */
  @Test
  void csrfFormNestedThroughAFragmentExpressionIsABuildError() {
    for (String[] f : FORMS) {
      Compilation c = slotPage(f[0], f[1], "~{::form}", "<form method=\"post\"><button>Go</button></form>");
      c.assertError("Nested forms are not allowed", PARSE_ERROR);
      assertNull(c.generatedSource("tessera.generated.PageTemplate"), f[1]);
    }
  }

  /** Positive control: the same shapes with flow content compile and render it in place. */
  @Test
  void flowFragmentThroughAFragmentExpressionRenders() {
    for (String[] f : FORMS) {
      Compilation c = slotPage(f[0], f[1], "~{::p}", "<p>x</p>").assertSuccess();
      assertEquals(
          "<div>\n<div><form action=\"https://other.example/search\"><input name=\"q\"/><p>x</p></form></div><p>x</p></div>",
          c.render("page", Map.of()), f[1]);
    }
  }

  private static Compilation forwarded(String arg, String tail) {
    return Compilation.builder()
        .template(
            "f.html",
            "<div " + NS + ">\n<div t:fragment=\"outer(Fragment body, boolean flag)\"><t:block"
                + " t:replace=\"~{f :: inner(${flag ? body : null})}\"></t:block></div>"
                + "<div t:fragment=\"inner(Fragment b)\"><form action=\"https://other.example/search\"><input name=\"q\"/>"
                + "<t:block t:insert=\"${b}\"/></form></div></div>")
        .template("page.html", "<div " + NS + ">\n<t:block t:replace=\"~{f :: outer(" + arg + ", true)}\"></t:block>" + tail + "</div>")
        .compile();
  }

  /** A ternary passed on as a fragment argument is bound to its branches in the callee. */
  @Test
  void forwardedTernaryIsVerifiedWhereTheCalleeInsertsIt() {
    forwarded("~{::form}", "<form method=\"post\"><button>Go</button></form>").assertError("Nested forms are not allowed", PARSE_ERROR);
    Compilation c = forwarded("~{::p}", "<p>x</p>").assertSuccess();
    assertEquals(
        "<div>\n<div><div><form action=\"https://other.example/search\"><input name=\"q\"/><p>x</p></form></div></div><p>x</p></div>",
        c.render("page", Map.of()));
  }

  private static Compilation listItem(String arg, String tail) {
    return Compilation.builder()
        .template(
            "f.html",
            "<div " + NS + ">\n<ul t:fragment=\"list(Fragment body, boolean flag, String s)\"><li><t:block"
                + " t:insert=\"${flag ? body : null}\"/>[[${s}]]</li></ul></div>")
        .template(
            "page.html",
            "<div " + NS + " t:params=\"String s\">\n<t:block t:replace=\"~{f :: list(" + arg + ", true, ${s})}\"></t:block>"
                + tail + "</div>")
        .compile();
  }

  /** A {@code ~{::li}} inserted in an {@code <li>} closes it: the value after it lands in the list. */
  @Test
  void listItemThroughATernaryIsVerifiedWhereItLands() {
    listItem("~{::li}", "<ul><li>x</li></ul>").assertError(PARSE_ERROR);
    Compilation c = listItem("~{::b}", "<b>x</b>").assertSuccess();
    assertEquals("<div>\n<ul><li><b>x</b>&lt;i&gt;</li></ul><b>x</b></div>", c.render("page", Map.of("s", "<i>")));
  }

  private static final String FRAGS_FUNCTIONS =
      """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateFunctions("frags")
      public final class Frags {
        private Frags() {}
        @TemplateFunction public static Fragment pass(Fragment f) { return f; }
      }
      """;

  /**
   * A {@code @TemplateFunction} that takes a Fragment could return a template-built value the
   * compiler can't trace, so its declaration is a build error.
   */
  @Test
  void fragmentFunctionParameterIsABuildError() {
    Compilation.builder()
        .java("com.example.Frags", FRAGS_FUNCTIONS)
        .template("f.html", crossOriginSlot("", "${#frags.pass(body)}"))
        .template("page.html", "<div " + NS + ">\n<t:block t:replace=\"~{f :: slot(~{::form}, true)}\"></t:block>"
            + "<form method=\"post\"><button>Go</button></form></div>")
        .compile()
        .assertError(
            "com/example/Frags.java:1:1:", "@TemplateFunction com.example.Frags.pass parameter f has type"
                + " dev.tessera.runtime.Fragment:", "a function may not take a Fragment");
    String varargs = FRAGS_FUNCTIONS.replace("Fragment pass(Fragment f)", "Fragment pass(Fragment... f)")
        .replace("return f;", "return f[0];");
    Compilation.builder()
        .java("com.example.Frags", varargs)
        .template("f.html", crossOriginSlot("", "${#frags.pass(body)}"))
        .compile()
        .assertError("@TemplateFunction com.example.Frags.pass parameter f has type dev.tessera.runtime.Fragment", "a function may not take a Fragment");
  }

  private static final String OR_NULL_FUNCTION =
      """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateFunctions("fx")
      public final class Fx {
        private Fx() {}
        @TemplateFunction public static Fragment orNull(Object o) { return o instanceof Fragment ? (Fragment) o : null; }
      }
      """;

  private static final String OR_NULL_VIEW =
      """
      package com.example;
      import dev.tessera.runtime.*;
      @TemplateView
      public final class Fv {
        public Fragment orNull(Object o) { return o instanceof Fragment ? (Fragment) o : null; }
      }
      """;

  /** A function or method that takes Object and returns a Fragment, applied to a Fragment parameter. */
  private static Compilation laundered(String javaName, String java, String params, String insert, String arg) {
    return Compilation.builder()
        .java(javaName, java)
        .template(
            "f.html",
            "<div " + NS + " t:import=\"com.example.*\">\n<div t:fragment=\"slot(Fragment body" + params + ")\"><form"
                + " action=\"https://other.example/search\"><input name=\"q\"/><t:block t:insert=\"" + insert
                + "\"></t:block></form></div></div>")
        .template(
            "page.html",
            "<div " + NS + " t:import=\"com.example.*\"" + (arg.isEmpty() ? "" : " t:params=\"Fv v\"")
                + ">\n<t:block t:replace=\"~{f :: slot(~{::form}" + arg
                + ")}\"></t:block><form method=\"post\"><button>Go</button></form></div>")
        .compile();
  }

  /**
   * The amendment's probe: a {@code @TemplateFunction} that takes {@code Object} hands back the
   * template-built {@code ~{::form}}; and the same through a {@code @TemplateView} method. Any call
   * is a build error, whatever its parameter types, also inside a ternary or passed on.
   */
  @Test
  void fragmentThroughAFunctionOrMethodCallIsABuildError() {
    for (String insert : List.of("${#fx.orNull(body)}", "${true ? #fx.orNull(body) : null}")) {
      Compilation c = laundered("com.example.Fx", OR_NULL_FUNCTION, "", insert, "");
      c.assertError("f.html:2:", "t:insert is a Fragment", UNTRACED, "no function or method call");
      assertNull(c.generatedSource("tessera.generated.FTemplate"), insert);
    }
    // A method with arguments is never callable from a template, so this one fails before #561's rule.
    for (String insert : List.of("${v.orNull(body)}", "${v.orNull(body) ?: null}")) {
      Compilation c = laundered("com.example.Fv", OR_NULL_VIEW, ", Fv v", insert, ", ${v}");
      c.assertError("f.html:2:", "method call 'orNull(...)' is not allowed");
      assertNull(c.generatedSource("tessera.generated.FTemplate"), insert);
    }
    Compilation.builder()
        .java("com.example.Fx", OR_NULL_FUNCTION)
        .template("f.html", crossOriginSlot("", "${body}"))
        .template(
            "page.html",
            "<div " + NS + ">\n<div t:fragment=\"outer(Fragment b)\"><t:block t:replace=\"~{f :: slot(${#fx.orNull(b)},"
                + " true)}\"></t:block></div></div>")
        .compile()
        .assertError("page.html:2:", "argument 1 ('body') of fragment f :: slot", UNTRACED);
  }

  /** Positive control: the same slots with the parameter itself compile and render a flow fragment. */
  @Test
  void fragmentParameterWithoutACallRenders() {
    Compilation c = Compilation.builder()
        .java("com.example.Fv", OR_NULL_VIEW)
        .template(
            "f.html",
            "<div " + NS + " t:import=\"com.example.*\">\n<div t:fragment=\"slot(Fragment body, Fv v)\"><form"
                + " action=\"https://other.example/search\"><input name=\"q\"/><t:block t:insert=\"${body ?: null}\">"
                + "</t:block></form></div></div>")
        .template(
            "page.html",
            "<div " + NS + " t:import=\"com.example.*\" t:params=\"Fv v\">\n<t:block t:replace=\"~{f :: slot(~{::p}, ${v})}\">"
                + "</t:block><p>x</p></div>")
        .compile()
        .assertSuccess();
    assertEquals(
        "<div>\n<div><form action=\"https://other.example/search\"><input name=\"q\"/><p>x</p></form></div><p>x</p></div>",
        c.render("page", Map.of("v", c.newInstance("com.example.Fv"))));
  }

  private static final String HOLDER = "package com.example; public record Holder(dev.tessera.runtime.Fragment body) {}";

  /** Fail closed: a Fragment the compiler can't trace to its candidates, inserted or passed on. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<t:block t:insert=\"${h.body}\"/>",
        "<t:block t:insert=\"${flag ? h.body : null}\"/>",
        "<t:block t:replace=\"~{f :: slot(${h.body}, true)}\"/>",
      })
  void untracedFragmentIsABuildError(String body) {
    Compilation.builder()
        .java("com.example.Holder", HOLDER)
        .template("f.html", crossOriginSlot("", "${body}"))
        .template("page.html", "<div " + NS + " t:import=\"com.example.Holder\" t:params=\"Holder h, boolean flag\">\n" + body + "</div>")
        .compile()
        .assertError("page.html:2:", UNTRACED, "a Fragment parameter, null, a t:with alias of one");
  }

  /** Fail closed at the alias: a t:with of a Fragment the compiler can't trace. */
  @Test
  void untracedFragmentAliasIsABuildError() {
    Compilation.builder()
        .java("com.example.Holder", HOLDER)
        .template("page.html", "<div " + NS + " t:import=\"com.example.Holder\" t:params=\"Holder h\">\n"
            + "<t:block t:with=\"f=${h.body}\"></t:block></div>")
        .compile()
        .assertError("page.html:2:", "t:with value 'f'", UNTRACED);
  }

  private static Compilation tableSlot(String insertPoint, String arg, String tail) {
    return Compilation.builder()
        .template("f.html", "<div " + NS + ">\n<div t:fragment=\"slot(Fragment body, boolean flag)\">" + insertPoint + "</div></div>")
        .template("page.html", "<div " + NS + " t:params=\"String s\">\n<t:block t:replace=\"~{f :: slot(" + arg + ", true)}\"></t:block>"
            + tail + "</div>")
        .compile();
  }

  /**
   * A table part is judged where it lands: a {@code ~{::tr}} in a {@code <tbody>} is accepted (also
   * through a ternary), and its cell's value stays escaped text there; in a {@code <div>} it is a
   * parse error. {@code FragmentTop} rejected the accepted case where the value was built.
   */
  @Test
  void tablePartIsJudgedWhereItLands() {
    String rows = "<table><tr><td>[[${s}]]</td></tr></table>";
    String cell = "<tr><td>&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;&lt;script&gt;x&lt;/script&gt;</td></tr>";
    for (String insert : List.of("${body}", "${flag ? body : null}")) {
      Compilation c = tableSlot("<table><tbody t:insert=\"" + insert + "\"></tbody></table>", "~{::tr}", rows).assertSuccess();
      assertEquals(
          "<div>\n<div><table><tbody>" + cell + "</tbody></table></div><table>" + cell + "</table></div>",
          c.render("page", Map.of("s", "</td></tr></table><script>x</script>")), insert);
      tableSlot("<div t:insert=\"" + insert + "\"></div>", "~{::tr}", rows).assertError(PARSE_ERROR);
    }
  }
}
