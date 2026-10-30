package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.testing.Compilation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vue/Alpine bindings of an ordinary target ({@code :prop}, {@code v-bind:prop}, {@code x-bind:prop})
 * take a JSON-serializable value, written as JSON (as inside {@code <script>}) and then
 * attribute-escaped (#246). Every other directive still rejects expressions (#235).
 */
class JsonBindingTest {

  private static final String[] HOSTILE = {
    "\"); alert(1); (\"",
    "'); alert(1); ('",
    "\" onmouseover=\"alert(1)",
    "&quot;); alert(1); (&quot;",
    "&#34;-alert(1)-&#34;",
    "</script><script>alert(1)</script>",
    "<!--<script>",
    "`${alert(1)}`",
    "{{ constructor.constructor('alert(1)')() }}",
    "\\\"); alert(1); //",
    "a b c\nd\re\tf\0g",
    "😀 \uD800 x",
    "*/ alert(1) /*",
  };

  private static Compilation compile(String params, String body) {
    return Compilation.builder()
        .java("com.example.Point", "package com.example; public record Point(int x, String label) {}")
        .java(
            "com.example.Api",
            "package com.example; @dev.tessera.runtime.TemplateView public class Api {\n"
                + "  private final java.util.List<Point> points;\n"
                + "  public Api(java.util.List<Point> points) { this.points = points; }\n"
                + "  public java.util.List<Point> getPoints() { return points; }\n"
                + "  public java.util.Map<String, Point> getByName() {\n"
                + "    java.util.Map<String, Point> m = new java.util.LinkedHashMap<>();\n"
                + "    for (Point p : points) { m.put(p.label(), p); } return m; }\n"
                + "  public java.util.Optional<Point> getFirst() { return points.stream().findFirst(); }\n"
                + "  public Point[] getArray() { return points.toArray(new Point[0]); }\n"
                + "  public Kind getKind() { return Kind.B; }\n"
                + "  public ClassLoader getLoader() { return getClass().getClassLoader(); }\n"
                + "}\n")
        .java("com.example.Kind", "package com.example; public enum Kind { A, B }")
        .java("com.example.Tree", "package com.example; public record Tree(String name, java.util.List<Tree> children) {}")
        .java("com.example.Pojo", "package com.example; public class Pojo { public String getName() { return \"p\"; } }")
        .java("com.example.Holder", "package com.example; public record Holder(Pojo pojo) {}")
        .template("t.html", "<div t:import=\"com.example.*\" t:params=\"" + params + "\">" + body + "</div>")
        .compile();
  }

  /** The decoded value of attribute {@code name}: the only HTML decoding a browser needs here. */
  private static String attribute(String html, String name) {
    Matcher m = Pattern.compile(" " + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(html);
    assertTrue(m.find(), () -> name + " not in " + html);
    String raw = m.group(1);
    assertFalse(raw.contains("<") || raw.contains(">") || raw.contains("'"), raw);
    String decoded = raw.replace("&quot;", "\"");
    assertFalse(decoded.replace("&amp;", "").contains("&"), () -> "unexpected character reference in " + raw);
    return decoded.replace("&amp;", "&");
  }

  /**
   * Evaluates {@code js} as a JavaScript expression that must be exactly one double-quoted string
   * literal (so the framework can only ever get a string out of it, never run code), following
   * the ECMAScript string-literal grammar for the escapes the JSON writer emits.
   */
  private static String evalStringLiteral(String js) {
    if (js.length() < 2 || js.charAt(0) != '"') {
      fail("not a string literal: " + js);
    }
    StringBuilder sb = new StringBuilder();
    int i = 1;
    while (true) {
      if (i >= js.length()) {
        fail("unterminated string literal: " + js);
      }
      char c = js.charAt(i++);
      if (c == '"') {
        break;
      }
      if (c == '\n' || c == '\r') {
        fail("line terminator in string literal: " + js);
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char e = js.charAt(i++);
      switch (e) {
        case 'u':
          sb.append((char) Integer.parseInt(js.substring(i, i + 4), 16));
          i += 4;
          break;
        case 'n': sb.append('\n'); break;
        case 'r': sb.append('\r'); break;
        case 't': sb.append('\t'); break;
        case 'b': sb.append('\b'); break;
        case 'f': sb.append('\f'); break;
        case '"': case '\\': case '/': sb.append(e); break;
        default: fail("unexpected escape \\" + e + " in " + js);
      }
    }
    assertEquals(js.length(), i, () -> "code after the string literal: " + js);
    return sb.toString();
  }

  @ParameterizedTest
  @ValueSource(strings = {":security", "v-bind:security", "x-bind:title", ":data-x", "V-BIND:Title"})
  void hostileStringIsAJsonStringLiteral(String name) {
    // t:attr takes names that start with a letter, so it gets the v-bind: form.
    String viaAttr = "v-bind:other";
    Compilation c =
        compile("String s", "<api-entry t:" + name + "=\"${s}\" t:attr=\"" + viaAttr + "=${s}\"></api-entry>")
            .assertSuccess();
    for (String h : HOSTILE) {
      String html = c.render("t", model("s", h));
      assertTrue(html.startsWith("<div><api-entry ") && html.endsWith("></api-entry></div>"), html);
      for (String n : new String[] {name, viaAttr}) {
        String js = attribute(html, n);
        // The same text as inside <script>: no '<', '&', '\'', '`', '$', U+2028/9 or controls.
        assertFalse(js.matches("(?s).*[<>&'`$\u2028\u2029\n\r\t\0].*"), js);
        assertEquals(h, evalStringLiteral(js), n);
      }
    }
  }

  @Test
  void exactOutput() {
    Compilation c = compile("String s", "<p t::title=\"${s}\" t:x-bind:class=\"${s}\">x</p>").assertSuccess();
    assertEquals(
        "<div><p :title=\"&quot;\\&quot;); alert(1); (\\&quot;\\u003c\\/script\\u003e&quot;\""
            + " x-bind:class=\"&quot;\\&quot;); alert(1); (\\&quot;\\u003c\\/script\\u003e&quot;\">x</p></div>",
        c.render("t", model("s", "\"); alert(1); (\"</script>")));
  }

  /** The text is the same as for [[...]] inside a script, for every JSON-able type. */
  @Test
  void sameJsonAsInsideScript() {
    String params = "Api api, Integer n, boolean b, Kind k, java.util.List<String> l, java.util.Map<String, Integer> m,"
        + " Tree t, java.util.Optional<Point> o, Point p, int[] a, java.time.LocalDate d";
    String[] values = {"api", "n", "b", "k", "l", "m", "t", "o", "p", "a", "d"};
    StringBuilder body = new StringBuilder();
    for (String v : values) {
      body.append("<p id=\"").append(v).append("\" t::v=\"${").append(v).append("}\">x</p><script>[[${")
          .append(v).append("}]]</script>");
    }
    Compilation c = compile(params, body.toString()).assertSuccess();
    Object p1 = c.newInstance("com.example.Point", 1, "a\"</script>");
    Object p2 = c.newInstance("com.example.Point", 2, "b");
    Object leaf = c.newInstance("com.example.Tree", "leaf", List.of());
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("x", 1);
    m.put("y", null);
    Map<String, Object> model = model(
        "api", c.newInstance("com.example.Api", List.of(p1, p2)), "n", 7, "b", true,
        "k", c.enumConstant("com.example.Kind", "A"), "l", List.of("a", "</script>"), "m", m,
        "t", c.newInstance("com.example.Tree", "root", List.of(leaf, leaf)), "o", Optional.of(p2), "p", p1,
        "a", new int[] {1, 2}, "d", java.time.LocalDate.of(2026, 1, 2));
    String html = c.render("t", model);
    Matcher m2 = Pattern.compile("<p id=\"(\\w+)\" :v=\"([^\"]*)\">x</p><script>(.*?)</script>").matcher(html);
    int found = 0;
    while (m2.find()) {
      assertEquals(m2.group(3), m2.group(2).replace("&quot;", "\""), m2.group(1));
      found++;
    }
    assertEquals(values.length, found, html);
    assertTrue(html.contains(":v=\"{&quot;points&quot;:[{&quot;x&quot;:1,"), html);
  }

  /** A null value is the JSON null, not an omitted attribute. */
  @Test
  void nullIsJsonNull() {
    Compilation c = compile("String s, Point p", "<p t::s=\"${s}\" t::p=\"${p}\">x</p>").assertSuccess();
    assertEquals("<div><p :s=\"null\" :p=\"null\">x</p></div>", c.render("t", model("s", null, "p", null)));
  }

  /** Cycles throw, as inside a script, before anything is written. */
  @Test
  void recursionIsBounded() {
    Compilation c = compile("Tree t", "<p t::t=\"${t}\">x</p>").assertSuccess();
    Object t = c.newInstance("com.example.Tree", "n", List.of());
    for (int i = 0; i < 70; i++) {
      t = c.newInstance("com.example.Tree", "n", List.of(t));
    }
    Object deep = t;
    assertThrows(RuntimeException.class, () -> c.render("t", model("t", deep)));
  }

  /** Static values and literals are fine; only the type decides. */
  @Test
  void literals() {
    Compilation c = compile("String s", "<p t::n=\"42\" t::s=\"'a'\" t::b=\"true\">x</p>").assertSuccess();
    assertEquals("<div><p :n=\"42\" :s=\"&quot;a&quot;\" :b=\"true\">x</p></div>", c.render("t", model("s", "x")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Holder", "dev.tessera.runtime.SafeHtml", "java.util.Map<Integer, String>",
      "dev.tessera.runtime.TrustedUrl", "java.util.List<dev.tessera.runtime.SafeHtml>"})
  void nonJsonTypeIsABuildError(String type) {
    compile(type + " v", "\n  <p t::data=\"${v}\">x</p>")
        .assertError("t.html:2:6", "cannot output a value of type", "in t::data", "serialized as JSON");
    compile(type + " v", "\n  <p t:attr=\"v-bind:data=${v}\">x</p>")
        .assertError("t.html:2:", "cannot output a value of type", "serialized as JSON");
  }

  @Test
  void nonJsonPropertyIsNamed() {
    compile("Holder h", "<p t:v-bind:h=\"${h}\">x</p>")
        .assertError("t.html:1:", "in t:v-bind:h", "property 'pojo' of com.example.Holder");
  }

  /** Never-view-safe getters are left out, as inside a script. */
  @Test
  void neverViewSafePropertiesAreLeftOut() {
    Compilation c = compile("Api api", "<p t::api=\"${api}\">x</p>").assertSuccess();
    String html = c.render("t", model("api", c.newInstance("com.example.Api", new ArrayList<>())));
    assertFalse(html.contains("loader"), html);
    assertTrue(html.contains("&quot;kind&quot;:&quot;B&quot;"), html);
  }

  /**
   * Bindings whose target is not an ordinary attribute, has modifiers or is dynamic, and every other
   * directive, still reject any expression, whatever its type (#235).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "v-bind", "x-bind", "v-bind.prop", "v-bind:title.prop", ":title.camel", ":title.attr", "x-bind:title.camel",
        ":href", "v-bind:href", "x-bind:href", ":src", ":action", ":formaction", ":style", "x-bind:style",
        ":onclick", "v-bind:onmouseover", ":innerHTML", ":innerhtml", ":outerHTML", ":is", ":inner-h-t-m-l",
        ":protocol", "v-bind:host", ":hostname", ":pathname", ":search", ":hash", ":port", ":username", ":password", ":srcdoc", ":v-html",
        ":x-data", ":x-on:click", ":hx-get", ":hx-vals", ":hx-on:click", ":data-bind", ":ng-click", ":_",
        ":script", ":data-script", ":xlink:href", ":srcset", "v-bind:srcset", ":imagesrcset", ":manifest", ".title", "@click", "v-on:click", "x-on:click", "x-data",
        "x-init", "v-html", "v-text", "v-model", "hx-on:click", "ng-bind", "data-bind",
      })
  void otherDirectivesStillReject(String name) {
    for (String type : new String[] {"String", "Integer", "Point"}) {
      compile(type + " v", "<a t:" + name + "=\"${v}\">x</a>")
          .assertError("t.html:1:", "t:" + name + " is not allowed", "run as JavaScript");
      compile(type + " v", "<a t:attr=\"" + name + "=${v}\">x</a>").assertError("t.html:1:");
    }
  }

  @Test
  void bindingOnScriptStyleMetaOrResourceIsRejected() {
    compile("String v", "<script t::title=\"${v}\"></script>").assertError("t.html:1:", "is not allowed");
    compile("String v", "<style t::media=\"${v}\"></style>").assertError("t.html:1:", "is not allowed");
    compile("String v", "<meta t::content=\"${v}\">").assertError("t.html:1:", "is not allowed");
    compile("String v", "<iframe t::src=\"${v}\"></iframe>").assertError("t.html:1:", "is not allowed");
    compile("String v", "<object t::data=\"${v}\"></object>").assertError("t.html:1:", "is not allowed");
    compile("String v", "<my-widget t::params=\"${v}\"></my-widget>").assertError("t.html:1:", "is not allowed");
  }

  /**
   * A binding whose target the HTML tree builder reads ({@code xmlns}; a {@code <template>}'s
   * {@code for} and {@code shadowroot*}) is not a JSON binding: the target is FORBIDDEN, so the
   * binding stays a directive and rejects any expression. {@code :is}, {@code :xmlns:xlink} and
   * bindings on {@code <meta>} ({@code :charset}) are rejected for their own reasons.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg t::xmlns=\"${v}\"></svg>",
        "<p t:v-bind:xmlns=\"${v}\">x</p>",
        "<template t::for=\"${v}\"><p>x</p></template>",
        "<template t:x-bind:shadowrootmode=\"${v}\"><p>x</p></template>",
        "<template t::shadowrootdelegatesfocus=\"${v}\"><p>x</p></template>",
        "<template shadowrootmode=\"open\" t:v-bind:shadowrootclonable=\"${v}\"><p>x</p></template>",
        "<template t::shadowrootserializable=\"${v}\"><p>x</p></template>",
        "<svg t::xmlns:xlink=\"${v}\"></svg>",
        "<p t::is=\"${v}\">x</p>",
        "<meta t::charset=\"${v}\">",
      })
  void bindingOfAnAttributeTheTreeBuilderReadsIsRejected(String body) {
    compile("String v", "\n" + body)
        .assertError("t.html:2:", "is not allowed", "Vue or Alpine binding", "run as JavaScript");
  }

  /** The same targets on an element whose tree builder ignores them stay JSON bindings. */
  @Test
  void bindingOfForOrShadowRootOutsideTemplateIsJson() {
    Compilation c =
        compile("String v", "<label t::for=\"${v}\">x</label><div t:x-bind:shadowrootmode=\"${v}\">x</div>")
            .assertSuccess();
    assertEquals(
        "<div><label :for=\"&quot;a&quot;\">x</label><div x-bind:shadowrootmode=\"&quot;a&quot;\">x</div></div>",
        c.render("t", model("v", "a")));
  }

  /** Literal (non-t:) bindings are the author's code and are copied verbatim. */
  @Test
  void literalBindingsAreUnchanged() {
    String markup = "<api-entry :security='[{\"a\": 1}]' v-bind:x=\"y + 1\" x-bind:class=\"open ? 'a' : ''\" :href=\"u\"></api-entry>";
    Compilation c = compile("String s", markup).assertSuccess();
    assertEquals("<div>" + markup + "</div>", c.render("t", model("s", "x")));
  }
}
