package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.compiler.testing.TestFragments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
        .java(
            "com.example.View",
            "package com.example; @dev.chainguard.thwart.runtime.TemplateView public class View {"
                + " public String getName() { return \"v\"; } }")
        .template("page.html", "<div t:import=\"com.example.*\" t:params=\"" + params + "\">" + body + "</div>")
        .compile();
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
    Object green = c.enumConstant("com.example.Color", "GREEN");
    assertEquals(
        "<div><i>42</i><i>1.50</i><i>GREEN</i><i>2024-02-29</i><i>&lt;b&gt;</i><i>true</i></div>",
        c.render(
            "page",
            model(
                "n", 41, "d", new BigDecimal("1.50"), "color", green, "date", LocalDate.of(2024, 2, 29), "o",
                Optional.of("<b>"), "b", true)));
  }

  @Test
  void textOfSafeHtmlStillEscapes() {
    Compilation c = compile("SafeHtml h", "<p t:text=\"h\">x</p>").assertSuccess();
    assertEquals("<div><p>&lt;b&gt;</p></div>", c.render("page", model("h", TestFragments.safeHtml("<b>"))));
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
  void textOfWholeRecordOrListIsBuildError() {
    // toString() would print every component, named in the template or not (#802).
    String asText = "com.example.Pet as text";
    compile("Pet pet", "<p t:text=\"${pet}\">x</p>").assertError("page.html:1:", asText, "one of its properties");
    compile("Pet pet", "<p t:text=\"${'x' + pet}\">x</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<p>[[${pet}]]</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<p t:title=\"${pet}\">x</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<a t:href=\"@{/p(q=${pet})}\">x</a>").assertError("page.html:1:", asText);
    compile("List<Pet> pets", "<p t:text=\"${pets}\">x</p>").assertError("page.html:1:", "List<com.example.Pet> as text");
    compile("Pet pet", "<p t:classappend=\"${pet}\">x</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<p t:text=\"#{m(${pet})}\">x</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<a t:href=\"@{/p/{id}(id=${pet})}\">x</a>").assertError("page.html:1:", asText);
    compile("Pet pet", "<p t:text=\"|x ${pet}|\">x</p>").assertError("page.html:1:", asText);
    compile("Pet pet", "<label t:for=\"${ids.next(pet)}\">x</label>").assertError("page.html:1:", asText);
    compile("View v", "<p t:text=\"${v}\">x</p>").assertError("page.html:1:", "com.example.View as text");
    compile("Pet[] pets", "<p t:text=\"${pets}\">x</p>").assertError("page.html:1:", "as text");
    compile("Optional<Pet> pet", "<p t:text=\"${pet}\">x</p>").assertError("page.html:1:", "as text");
    compile("Map<String, Pet> m", "<p t:text=\"${m}\">x</p>").assertError("page.html:1:", "as text");
    // Properties, List<String> and enums still print.
    Compilation ok = compile(
            "View v, List<String> xs, Color color",
            "<p t:text=\"${v.name}\">x</p><a t:href=\"@{/p(q=${xs})}\">x</a><p t:text=\"${color}\">x</p>")
        .assertSuccess();
    assertEquals(
        "<div><p>v</p><a href=\"/p?q=a&amp;q=b\">x</a><p>RED</p></div>",
        ok.render("page", model("v", ok.newInstance("com.example.View"), "xs", List.of("a", "b"), "color", ok.enumConstant("com.example.Color", "RED"))));
    // JSON writes the components it is given, not toString(): still allowed.
    Compilation c = compile("Pet pet", "<script type=\"application/json\" t:text=\"pet\"></script>").assertSuccess();
    assertEquals(
        "<div><script type=\"application/json\">{\"name\":\"Rex\"}</script></div>",
        c.render("page", model("pet", c.newInstance("com.example.Pet", "Rex"))));
  }

  @Test
  void textOnJsonScriptIsJson() {
    Compilation c = compile(
            "String s, List<Integer> xs",
            "<script type=\"application/json\" t:text=\"s\">x</script><script type=\"application/json\" t:text=\"xs\"></script>")
        .assertSuccess();
    assertEquals(
        "<div><script type=\"application/json\">\"\\u003c\\/script\\u003e\\u003cscript\\u003e\"</script>"
            + "<script type=\"application/json\">[1,2]</script></div>",
        c.render("page", model("s", "</script><script>", "xs", List.of(1, 2))));
  }

  @Test
  void textOnScriptRejectsNonJsonTypes() {
    compile("SafeHtml h", "<script type=\"application/json\" t:text=\"h\"></script>").assertError("page.html:1:", "inside <script>", "JSON");
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
    Compilation c = compile("SafeHtml h", "<div t:utext=\"h\">x</div>").assertSuccess();
    assertEquals("<div><div><b>bold</b></div></div>", c.render("page", model("h", TestFragments.safeHtml("<b>bold</b>"))));
    assertEquals("<div><div></div></div>", c.render("page", model()));
  }

  @Test
  void utextWithStringIsBuildErrorNamingSafeHtml() {
    compile("String s", "<p t:utext=\"s\">x</p>").assertError("page.html:1:", "t:utext", "SafeHtml", "java.lang.String");
    compile("String s", "<p t:utext=\"'<b>' + s\">x</p>").assertError("SafeHtml");
    compile("String s", "<p t:utext=\"null\">x</p>").assertError("SafeHtml", "null");
  }

  /** Negative (#664): Java can't wrap a string as {@code SafeHtml} without a check; there is no {@code unchecked}. */
  @Test
  void safeHtmlUncheckedDoesNotExist() {
    Compilation.builder()
        .java("com.example.Raw", "package com.example; public final class Raw {"
            + " static Object raw(String s) { return dev.chainguard.thwart.runtime.SafeHtml.unchecked(s); } }")
        .compile()
        .assertError("cannot find symbol", "unchecked");
  }

  @Test
  void utextWithUncheckedMessageIsSanitizedAtRender() {
    compile("String s", "<p t:utext=\"#{home.welcome}\">x</p>")
        .assertSuccess();
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
        "<div><script>var a = (\"\\u003c\\/script\\u003e\\u003cscript\\u003ealert(1)\\/\\/\"); var m = ({\"k\":1});</script></div>",
        out);
  }

  @Test
  void inlinedInScriptRejectsNonJsonTypes() {
    compile("SafeHtml h", "<script>var p = [[h]];</script>").assertError("page.html:1:", "inside <script>");
    compile("Map<Integer, String> m", "<script>var p = [[m]];</script>").assertError("inside <script>");
  }

  @Test
  void inlinedInScriptAcceptsEveryJavaTimeTypeEscapersSerializes() {
    String[] types = {
      "Instant", "LocalDate", "LocalTime", "LocalDateTime", "OffsetTime", "OffsetDateTime", "ZonedDateTime", "Year",
      "YearMonth", "MonthDay", "Duration", "Period", "ZoneId", "ZoneOffset", "DayOfWeek", "Month"
    };
    for (String type : types) {
      Compilation.builder()
          .template("page.html", "<div t:import=\"java.time.*\" t:params=\"" + type + " v\"><script>var v = [[v]];</script></div>")
          .compile()
          .assertSuccess();
    }
  }

  @Test
  void inlinedInScriptRendersZoneOffset() {
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:import=\"java.time.*\" t:params=\"ZoneOffset zone, ZoneId id\"><script>var z = [[zone]]; var i = [[id]];</script></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><script>var z = (\"+02:00\"); var i = (\"Europe\\/Paris\");</script></div>",
        c.render("page", model("zone", ZoneOffset.ofHours(2), "id", ZoneId.of("Europe/Paris"))));
  }

  @Test
  void inlinedInScriptRejectsJavaTimeTypesEscapersCannotSerialize() {
    // Regression: DateTimeException used to compile here and throw at render.
    for (String type : List.of("java.time.DateTimeException", "java.time.chrono.JapaneseDate", "java.time.zone.ZoneRules")) {
      Compilation.builder()
          .template("page.html", "<div t:params=\"" + type + " v\"><script>var v = [[v]];</script></div>")
          .compile()
          .assertError("page.html:1:", type);
    }
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
    Compilation c = compile("SafeHtml h", "<div>a [(h)] b</div>").assertSuccess();
    assertEquals("<div><div>a <i>x</i> b</div></div>", c.render("page", model("h", TestFragments.safeHtml("<i>x</i>"))));
    compile("String s", "<p>[(s)]</p>").assertError("page.html:1:", "[(...)]", "SafeHtml", "java.lang.String");
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
}
