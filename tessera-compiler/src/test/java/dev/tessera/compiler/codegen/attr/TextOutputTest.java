package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.SafeHtml;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** End-to-end tests for {@code t:text}, {@code t:utext}, {@code [[...]]} and {@code [(...)]}. */
class TextOutputTest {
  private static final String XSS = "<script>alert('x')</script>&\"";
  private static final String XSS_ESCAPED = "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;";

  private static Compilation compile(String params, String body) {
    return Compilation.builder()
        .java("com.example.Color", "package com.example; public enum Color { RED, GREEN }")
        .java("com.example.Pet", "package com.example; public record Pet(String name) {}")
        .java("com.example.Pojo", "package com.example; public class Pojo { public String getName() { return \"p\"; } }")
        .template("page.html", "<div t:import=\"com.example.*\" t:params=\"" + params + "\">" + body + "</div>")
        .compile();
  }

  private static Map<String, Object> model(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // ---- t:text

  @Test
  void textEscapesMarkup() {
    Compilation c = compile("String s", "<p t:text=\"s\">placeholder</p>").assertSuccess();
    assertEquals("<div><p>" + XSS_ESCAPED + "</p></div>", c.render("page", model("s", XSS)));
  }

  @Test
  void textNullRendersEmpty() {
    Compilation c = compile("String s, Optional<String> o", "<p t:text=\"s\">x</p><b t:text=\"o\">y</b>").assertSuccess();
    assertEquals("<div><p></p><b></b></div>", c.render("page", model("s", null, "o", null)));
    assertEquals("<div><p></p><b></b></div>", c.render("page", model("o", Optional.empty())));
  }

  @Test
  void textValueTypes() {
    Compilation c =
        compile(
                "int n, BigDecimal d, Color color, LocalDate date, Optional<String> o, boolean b",
                "<i t:text=\"n + 1\">.</i><i t:text=\"d\">.</i><i t:text=\"color\">.</i>"
                    + "<i t:text=\"date\">.</i><i t:text=\"o\">.</i><i t:text=\"b\">.</i>")
            .assertSuccess();
    Object red = enumConstant(c, "com.example.Color", "GREEN");
    assertEquals(
        "<div><i>42</i><i>1.50</i><i>GREEN</i><i>2024-02-29</i><i>&lt;b&gt;</i><i>true</i></div>",
        c.render(
            "page",
            model(
                "n", 41, "d", new BigDecimal("1.50"), "color", red, "date", LocalDate.of(2024, 2, 29), "o",
                Optional.of("<b>"), "b", true)));
  }

  @Test
  void textOfSafeHtmlStillEscapes() {
    Compilation c = compile("SafeHtml h", "<p t:text=\"h\">x</p>").assertSuccess();
    assertEquals("<div><p>&lt;b&gt;</p></div>", c.render("page", model("h", SafeHtml.unchecked("<b>"))));
  }

  @Test
  void textOfRecordIsAllowedAndEscaped() {
    Compilation c = compile("Pet pet", "<p t:text=\"pet.name\">x</p>").assertSuccess();
    Object pet = c.newInstance("com.example.Pet", "<i>Rex</i>");
    assertEquals("<div><p>&lt;i&gt;Rex&lt;/i&gt;</p></div>", c.render("page", model("pet", pet)));
  }

  @Test
  void textOfObjectIsBuildError() {
    // Object cannot even be declared; a non-view-safe class cannot be output.
    compile("Object o", "<p t:text=\"o\">x</p>").assertError("page.html:1:", "Object");
    compile("Pojo p", "<p>x</p>").assertError("page.html:1:", "not view-safe");
  }

  @Test
  void textOnScriptIsJson() {
    Compilation c = compile("String s, List<Integer> xs", "<script t:text=\"s\">x</script><script t:text=\"xs\"></script>")
        .assertSuccess();
    assertEquals(
        "<div><script>\"\\u003c\\/script\\u003e\\u003cscript\\u003e\"</script><script>[1,2]</script></div>",
        c.render("page", model("s", "</script><script>", "xs", List.of(1, 2))));
  }

  @Test
  void textOnScriptRejectsNonJsonTypes() {
    compile("Pet pet", "<script t:text=\"pet\"></script>").assertError("page.html:1:", "inside <script>", "JSON");
  }

  @Test
  void textOnStyleIsBuildError() {
    compile("String s", "<style t:text=\"s\"></style>").assertError("page.html:1:", "<style>");
    compile("String s", "<script type=\"text/template\" t:text=\"s\"></script>")
        .assertError("page.html:1:", "not JavaScript or JSON");
  }

  @Test
  void textUnknownIdentifierIsBuildError() {
    compile("String s", "<p t:text=\"nope\">x</p>").assertError("page.html:1:", "unknown identifier 'nope'");
  }

  // ---- t:utext

  @Test
  void utextWithSafeHtmlRendersRaw() {
    Compilation c = compile("SafeHtml h", "<p t:utext=\"h\">x</p>").assertSuccess();
    assertEquals("<div><p><b>bold</b></p></div>", c.render("page", model("h", SafeHtml.unchecked("<b>bold</b>"))));
    assertEquals("<div><p></p></div>", c.render("page", model()));
  }

  @Test
  void utextWithStringIsBuildErrorNamingSafeHtml() {
    compile("String s", "<p t:utext=\"s\">x</p>").assertError("page.html:1:", "t:utext", "SafeHtml", "java.lang.String");
    compile("String s", "<p t:utext=\"'<b>' + s\">x</p>").assertError("SafeHtml");
    compile("String s", "<p t:utext=\"null\">x</p>").assertError("SafeHtml", "null");
  }

  @Test
  void utextWithMessageIsBuildErrorNamingSafeHtml() {
    compile("String s", "<p t:utext=\"#{home.welcome}\">x</p>").assertError("page.html:1:", "SafeHtml", "message");
  }

  @Test
  void utextInScriptIsBuildError() {
    compile("SafeHtml h", "<script t:utext=\"h\"></script>").assertError("page.html:1:", "t:utext");
  }

  // ---- [[...]] and [(...)]

  @Test
  void inlinedTextEscapes() {
    Compilation c = compile("String s, int n", "<p>Hi [[s]], you have [[ n ]] [[${n}]]!</p>").assertSuccess();
    assertEquals("<div><p>Hi " + XSS_ESCAPED + ", you have 3 3!</p></div>", c.render("page", model("s", XSS, "n", 3)));
  }

  @Test
  void inlinedNullRendersEmpty() {
    Compilation c = compile("String s", "<p>[[s]]|</p>").assertSuccess();
    assertEquals("<div><p>|</p></div>", c.render("page", model()));
  }

  @Test
  void textWithoutExpressionsIsVerbatim() {
    Compilation c = compile("String s", "<p>a [b] [ [c] ] [[ unterminated</p>").assertSuccess();
    assertEquals("<div><p>a [b] [ [c] ] [[ unterminated</p></div>", c.render("page", model()));
  }

  @Test
  void closingBracketsInsideStringLiteral() {
    Compilation c = compile("String s", "<p>[[s + ']]']]</p>").assertSuccess();
    assertEquals("<div><p>x]]</p></div>", c.render("page", model("s", "x")));
  }

  @Test
  void inlinedInScriptIsJsonAndCannotBreakOut() {
    Compilation c =
        compile("String s, Map<String, Integer> m", "<script>var a = [[s]]; var m = [[m]];</script>").assertSuccess();
    String out = c.render("page", model("s", "</script><script>alert(1)//", "m", Map.of("k", 1)));
    assertEquals(
        "<div><script>var a = \"\\u003c\\/script\\u003e\\u003cscript\\u003ealert(1)\\/\\/\"; var m = {\"k\":1};</script></div>",
        out);
  }

  @Test
  void inlinedInScriptRejectsNonJsonTypes() {
    compile("Pet pet", "<script>var p = [[pet]];</script>").assertError("page.html:1:", "inside <script>");
    compile("Map<Integer, String> m", "<script>var p = [[m]];</script>").assertError("inside <script>");
  }

  @Test
  void inlinedInStyleIsBuildError() {
    compile("String s", "<style>p { color: [[s]]; }</style>").assertError("page.html:1:", "<style>");
  }

  @Test
  void inlinedInNonJsScriptIsBuildError() {
    compile("String s", "<script type=\"text/x-template\">[[s]]</script>").assertError("page.html:1:", "not JavaScript");
  }

  @Test
  void unescapedInlineAcceptsSafeHtmlOnly() {
    Compilation c = compile("SafeHtml h", "<p>a [(h)] b</p>").assertSuccess();
    assertEquals("<div><p>a <i>x</i> b</p></div>", c.render("page", model("h", SafeHtml.unchecked("<i>x</i>"))));
    compile("String s", "<p>[(s)]</p>").assertError("page.html:1:", "[(...)]", "SafeHtml", "java.lang.String");
    compile("String s", "<p>[(#{x})]</p>").assertError("SafeHtml", "message");
  }

  @Test
  void unescapedInlineInScriptIsBuildError() {
    compile("SafeHtml h", "<script>[(h)]</script>").assertError("page.html:1:", "[(...)]", "<script>");
  }

  @Test
  void inlineErrorPositionIsExact() {
    Compilation c =
        Compilation.builder()
            .template("pos.html", "<div t:params=\"String s\">\n  <p>ok [[s]] then [[missing]]</p>\n</div>")
            .compile();
    c.assertError("pos.html:2:22", "unknown identifier 'missing'");
  }

  private static Object enumConstant(Compilation c, String cls, String name) {
    try {
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object v = Enum.valueOf((Class) c.classLoader().loadClass(cls), name);
      return v;
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }
}
