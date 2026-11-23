package dev.chainguard.thwart.compiler.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.DefaultLinks;
import dev.chainguard.thwart.runtime.Forms;
import dev.chainguard.thwart.runtime.Messages;
import dev.chainguard.thwart.runtime.RenderContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A leading {@code Locale} parameter of a template function receives the render locale (#125). */
class LocaleFunctionTest {
  private static final String LANG =
      """
      package com.example;
      import dev.chainguard.thwart.runtime.*;
      @TemplateFunctions("lang")
      public final class Lang {
        private Lang() {}
        @TemplateFunction public static String tag(java.util.Locale locale) { return locale.toLanguageTag(); }
        @TemplateFunction public static String greet(java.util.Locale locale, String name) {
          return (locale.getLanguage().equals("de") ? "Hallo " : "Hello ") + name;
        }
      }
      """;

  private static final String PAGE =
      """
      <p xmlns:t="https://thwart.chainguard.dev" t:import="java.math.BigDecimal, java.time.LocalDate"
         t:params="BigDecimal amount, LocalDate day"><span t:text="${numbers.formatInteger(amount, 1, 'DEFAULT')}">1</span>|\
      <span t:text="${#numbers.formatDecimal(amount, 1, 2)}">1</span>|\
      <span t:text="${numbers.formatDecimal(amount, 1, 'COMMA', 2, 'POINT')}">1</span>|\
      <span t:text="${numbers.formatPercent(0.125, 1, 1)}">1</span>|\
      <span t:text="${temporals.format(day, 'd MMMM yyyy')}">d</span>|\
      <span t:text="${lang.tag()}">l</span>|<span t:text="${lang.greet('Ann')}">g</span></p>""";

  private static RenderContext ctx(Locale locale) {
    return new RenderContext(locale, Messages.NONE, new DefaultLinks(""), Forms.NONE);
  }

  @Test
  void builtinsAndUserFunctionsGetTheRenderLocale() {
    Compilation c =
        Compilation.builder().java("com.example.Lang", LANG).template("page.html", PAGE).compile().assertSuccess();
    Map<String, Object> model = Map.of("amount", new BigDecimal("-2550.5"), "day", LocalDate.of(2024, 3, 5));
    assertEquals(
        "<p><span>-2.550</span>|<span>-2550,50</span>|<span>-2,550.50</span>|<span>12,5 %</span>|"
            + "<span>5 März 2024</span>|<span>de-DE</span>|<span>Hallo Ann</span></p>",
        c.render("page", model, ctx(Locale.GERMANY)).replace(' ', ' '));
    assertEquals(
        "<p><span>-2,550</span>|<span>-2550.50</span>|<span>-2,550.50</span>|<span>12.5%</span>|"
            + "<span>5 March 2024</span>|<span>en-US</span>|<span>Hello Ann</span></p>",
        c.render("page", model, ctx(Locale.US)));
    assertEquals(
        "<p><span>-2,550</span>|<span>-2550.50</span>|<span>-2,550.50</span>|<span>12.5%</span>|"
            + "<span>5 March 2024</span>|<span>und</span>|<span>Hello Ann</span></p>",
        c.render("page", model));
  }

  @Test
  void theLocaleIsNotATemplateArgument() {
    Compilation.builder()
        .java("com.example.Lang", LANG)
        .template("page.html", "<p xmlns:t=\"https://thwart.chainguard.dev\" t:text=\"${lang.greet('x', 'Ann')}\">g</p>")
        .compile()
        .assertError("page.html:1:", "no single applicable lang.greet for arguments (");
  }

  @Test
  void onlyALeadingLocaleIsSupplied() {
    String bad =
        """
        package com.example;
        import dev.chainguard.thwart.runtime.*;
        @TemplateFunctions("bad")
        public final class Bad {
          private Bad() {}
          @TemplateFunction public static String late(String s, java.util.Locale locale) { return s; }
        }
        """;
    Compilation.builder()
        .java("com.example.Bad", bad)
        .template("page.html", "<p xmlns:t=\"https://thwart.chainguard.dev\" t:text=\"${bad.late('x')}\">g</p>")
        .compile()
        .assertError("page.html:1:", "no single applicable bad.late for arguments (");
  }
}
