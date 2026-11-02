package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.UpdateExpression;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule 3 of #583 (#584): a {@code data-*} attribute, and an {@code aria-*} name outside WAI-ARIA
 * 1.2, takes only a framework-neutral computed value; every other name outside HTML ({@code hx-*},
 * {@code x-*}, {@code v-*}, a custom element's own attribute, ...) is off the closed allow-list (#761)
 * and takes none. Neutral: an exact-class number in plain decimal, a boolean, a same-origin {@code @{}} path
 * with no query, or a choice between those and literals. Checked at build time by static type and
 * expression shape; numbers and paths are re-checked when rendered.
 */
class NeutralValuesTest {

  private static Compilation compile(String body) {
    return Compilation.builder()
        .java("com.example.Item", "package com.example; public record Item(long id, String name) {}")
        .template(
            "t.html",
            "<div xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"String s, Item i,"
                + " boolean flag, java.math.BigDecimal big, double d, java.util.List<String> tags\">" + body
                + "</div>")
        .compile();
  }

  private static String render(Compilation c, Object... kv) {
    Map<String, Object> model = new HashMap<>();
    model.put("s", "");
    model.put("i", c.newInstance("com.example.Item", 7L, "n"));
    model.put("flag", true);
    model.put("big", BigDecimal.ONE);
    model.put("d", 0.5);
    model.put("tags", List.of());
    for (int k = 0; k < kv.length; k += 2) {
      model.put((String) kv[k], kv[k + 1]);
    }
    return c.render("t", model);
  }

  // ---- negative: computed strings outside HTML attributes are build errors (#584's probes) ----

  @ParameterizedTest
  @ValueSource(strings = {"data-on-click", "data-hx-on:click", "data-x-html", "data-v-html", "data-name", "data-hx-vals",
      "data-x-data", "data-bind", "data-hx-get", "data-hx-post", "data-hx-push-url", "data-up-on-loaded", "data-ng-init",
      "aria-foo", "DATA-X"})
  void computedStringIsABuildError(String name) {
    compile("<p t:" + name + "=\"${s}\">x</p>")
        .assertError("t.html:1:", "t:" + name + " is not allowed", "framework-neutral", "not a java.lang.String");
  }

  /**
   * #761: a name outside HTML that is not {@code data-*} or {@code aria-*} takes no computed value,
   * neutral or not, with a fix-it naming the {@code data-*} spelling.
   */
  @ParameterizedTest
  @ValueSource(strings = {"hx-on:click", "x-html", "v-html", "hx-vals", "x-data", "foo", "hx-get", "hx-push-url",
      "up-on-loaded", "ng-init", "_", "hx-action", "v-bind:title", "x-bind:title"})
  void namesOffTheListTakeNoValue(String name) {
    compile("<p t:" + name + "=\"${i.id}\">x</p>")
        .assertError("t.html:1:", "t:" + name + " is not allowed", "is not on Tessera's list", "t:data-");
  }

  /**
   * Names outside {@code [A-Za-z_][A-Za-z0-9_.:-]*}, such as Vue's {@code :x} and {@code @x}, take no
   * computed value at all, neutral or not: stricter than rule 3, as before for any such name.
   */
  @ParameterizedTest
  @ValueSource(strings = {":title", "@click", "#default", ".prop"})
  void namesOutsideThePlainGrammarTakeNoValue(String name) {
    compile("<p t:" + name + "=\"${i.id}\">x</p>").assertError("t.html:1:");
  }

  /** The fix-it names the recipes: element text or a standard inert attribute, hidden inputs, a JSON block. */
  @Test
  void fixItNamesTheRecipes() {
    compile("<p t:data-name=\"${s}\">x</p>")
        .assertError("t.html:1:", "element text", "title", "hidden inputs", "<script type=\"application/json\">");
  }

  private static Compilation compileWithMessages(String body) {
    return Compilation.builder()
        .messages("tip=Hello\nother=World")
        .localeMessages("de", "tip=Hallo\nother=Welt")
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"boolean flag\">" + body + "</div>")
        .compile();
  }

  /**
   * A message is not a literal in a neutral attribute, even a {@code #{key}} with no arguments, alone
   * or in a choice (#629, guide section 4.4): bundles are edited outside template review, and a
   * framework may run what it reads there. In a standard inert attribute it is text as usual.
   */
  @ParameterizedTest
  @ValueSource(strings = {"data-tip", "data-hx-vals", "data-x-data", "data-v-html", "data-bind"})
  void messageIsNotANeutralValue(String name) {
    compileWithMessages("<p t:" + name + "=\"#{tip}\">x</p>").assertError("t.html:1:", "framework-neutral");
    compileWithMessages("<p t:" + name + "=\"${flag} ? #{tip} : 'x'\">x</p>").assertError("t.html:1:", "neutral");
    compileWithMessages("<p t:" + name + "=\"${flag} ? #{tip} : #{other}\">x</p>").assertError("t.html:1:", "neutral");
  }

  @Test
  void messageInAnInertAttributeIsText() {
    compileWithMessages("<p t:title=\"#{tip}\">x</p>").assertSuccess();
  }

  /** A custom element's own attributes are off the list (#761); there is no prop exemption. */
  @Test
  void customElementAttributeTakesNoValue() {
    compile("<x-widget t:html=\"${s}\"></x-widget>").assertError("t.html:1:", "t:html is not allowed", "not on");
    compile("<x-widget t:heading=\"${i.id}\"></x-widget>").assertError("t.html:1:", "not on");
    compile("<x-widget t:data-heading=\"${i.name}\"></x-widget>").assertError("t.html:1:", "neutral");
    // Its standard attributes are classified as on any element.
    compile("<x-widget t:title=\"${s}\"></x-widget>").assertSuccess();
    compile("<x-widget t:target=\"${s}\"></x-widget>").assertError("t.html:1:", "not an inert-text attribute");
  }

  /** Other values that are not neutral: enums, chars, collections, safe types. */
  @Test
  void otherTypesAreBuildErrors() {
    compile("<p t:data-x=\"${tags}\">x</p>").assertError("t.html:1:", "framework-neutral");
    compile("<p t:data-x=\"${s.charAt(0)}\">x</p>").assertError("t.html:1:");
  }

  /**
   * {@code <component>} gets no special handling: a static {@code is} must be a valid custom
   * element name, so nothing reading it can render a {@code <script>} where the compiler saw text.
   */
  @Test
  void staticIsMustNameACustomElement() {
    compile("<component is=\"script\" t:text=\"${s}\"></component>")
        .assertError("t.html:1:", "is=\"script\" on <component> is not allowed", "custom element");
    for (String v : new String[] {"iframe", "vue:script", "My-el", "font-face", "", "xı-y"}) {
      compile("<component is=\"" + v + "\"></component>").assertError("t.html:1:", "is not allowed");
    }
    compile("<component is=\"my-widget\" t:text=\"${s}\"></component>").assertSuccess();
    compile("<button is=\"fancy-button\">x</button>").assertSuccess();
  }

  /** String-typed pieces inside a concatenation or a choice are still strings. */
  @Test
  void stringInsideAChoiceOrConcatenationIsABuildError() {
    compile("<p t:data-x=\"${flag} ? ${s} : 'a'\">x</p>").assertError("t.html:1:", "neutral");
    compile("<p t:data-x=\"'a' + ${s}\">x</p>").assertError("t.html:1:", "neutral");
    compile("<p t:data-x=\"'a' + ${i.id}\">x</p>").assertError("t.html:1:", "neutral");
    compile("<p t:data-x=\"|a${i.id}|\">x</p>").assertError("t.html:1:", "neutral");
    compile("<p t:data-x=\"${s} ?: 'a'\">x</p>").assertError("t.html:1:", "neutral");
  }

  /** A link that could leave the origin, has a query, or holds characters outside the alphabet. */
  @ParameterizedTest
  @ValueSource(strings = {"@{//evil.example/x}", "@{/\\evil.example/x}", "@{https://evil.example/}", "@{x/y}",
      "@{javascript:alert(1)}", "@{~/x}", "@{/a?b=c}", "@{/a#b}", "@{/a=b}", "@{/a(b)}", "@{/a'b}", "@{/a:b}",
      "@{/a@b}", "@{/a+b}", "@{/a%zz}", "@{/items(q=${s})}", "@{/items/{id}(id=${i.id},q=${s})}"})
  void nonNeutralLinkIsABuildError(String link) {
    compile("<p t:data-url=\"" + link + "\">x</p>").assertError("t.html:1:");
  }

  // ---- runtime backstops ----

  /** A path variable can't spell {@code --} or leave the alphabet: both are percent-encoded. */
  @Test
  void pathVariablesAreEncodedIntoTheAlphabet() {
    Compilation c = compile("<p t:data-url=\"@{/a/{x}/b--c(x=${s})}\" t:data-tags=\"@{/t/{x}(x=${tags})}\">x</p>")
        .assertSuccess();
    assertEquals("<div><p data-url=\"/a/-%2D-location.port-%2D/b-%2Dc\" data-tags=\"/t/a%2Cb\">x</p></div>",
        render(c, "s", "---location.port--", "tags", List.of("a", "b")));
  }

  /** A BigDecimal subclass fails the render (#553's exact-class rule); NaN and Infinity too. */
  @Test
  void numberBackstops() {
    Compilation c = compile("<p t:data-big=\"${big}\" t:data-d=\"${d}\">x</p>").assertSuccess();
    BigDecimal hostile = new BigDecimal("1") {
      @Override
      public String toString() {
        return "alert(1)";
      }

      @Override
      public String toPlainString() {
        return "alert(1)";
      }
    };
    assertThrows(RuntimeException.class, () -> render(c, "big", hostile));
    assertThrows(RuntimeException.class, () -> render(c, "d", Double.NaN));
    assertThrows(RuntimeException.class, () -> render(c, "d", Double.POSITIVE_INFINITY));
    assertEquals("<div><p data-big=\"1000\" data-d=\"0.000000010\">x</p></div>",
        render(c, "big", new BigDecimal("1E+3"), "d", 1e-8));
  }

  // ---- positive controls ----

  @Test
  void sameOriginLinkWithPathVariable() {
    Compilation c = compile("<p t:data-hx-get=\"@{/items/{id}(id=${i.id})}\">x</p>").assertSuccess();
    assertEquals("<div><p data-hx-get=\"/items/7\">x</p></div>", render(c));
  }

  @Test
  void numbersBooleansAndChoices() {
    Compilation c =
        compile("<p t:data-id=\"${i.id}\" t:data-open=\"${flag}\" t:data-mode=\"${flag} ? 'a' : 'b'\""
            + " t:data-big=\"${big}\" t:data-sum=\"${i.id + 1}\" t:data-or=\"${flag} ? @{/a} : 0\">x</p>").assertSuccess();
    assertEquals("<div><p data-id=\"7\" data-open=\"true\" data-mode=\"a\" data-big=\"1.5\" data-sum=\"8\""
        + " data-or=\"/a\">x</p></div>", render(c, "big", new BigDecimal("1.5")));
    assertEquals("<div><p data-id=\"7\" data-open=\"false\" data-mode=\"b\" data-big=\"1.5\" data-sum=\"8\""
        + " data-or=\"0\">x</p></div>", render(c, "big", new BigDecimal("1.5"), "flag", false));
  }

  /**
   * A neutral value goes into any data-* name, on a custom element too; it writes
   * no JSON (#599 deleted jsonAttr), and a null leaves the attribute out.
   */
  @Test
  void neutralValuesInAnyDataName() {
    Compilation c = compile("<x-widget t:data-hx-vals=\"${i.id}\" t:data-x-show=\"${flag}\" t:data-count=\"${i.id}\""
        + " t:aria-foo=\"${big}\"></x-widget>").assertSuccess();
    assertEquals("<div><x-widget data-hx-vals=\"7\" data-x-show=\"true\" data-count=\"7\" aria-foo=\"1.5\"></x-widget>"
        + "</div>", render(c, "big", new BigDecimal("1.5")));
    assertEquals("<div><x-widget data-hx-vals=\"7\" data-x-show=\"true\" data-count=\"7\"></x-widget></div>",
        render(c, "big", null));
  }

  /**
   * The looser case is safe: a framework name that took no expression (#235) now takes a neutral
   * value, and whatever JavaScript evaluator reads it can neither call, assign nor increment or
   * decrement anything (#583's research: a value starting with {@code /} is a regular expression
   * literal, and {@code --} was the only write primitive left). Every rendered neutral value, with
   * hostile path variables, parses as JavaScript with no call, assignment or update, or not at all.
   */
  @Test
  void neutralOutputsRunNothingAsJavaScript() {
    Compilation c = compile("<p t:data-hx-on:click=\"@{/a/{x}/b--c(x=${s})}\" t:data-x-data=\"${d}\" t:data-v-html=\"${big}\""
        + " t:data-x-init=\"${flag}\" t:data-on-load=\"${i.id}\">x</p>").assertSuccess();
    List<String> values = new ArrayList<>();
    for (String s : new String[] {"--location.port--", "-document.cookie--", "x=alert(1)", "a'+alert(1)+'", "${alert(1)}",
        "a;alert(1)", " alert(1)", "a\nb", "../..//evil", "-%2D-", "a%", "é", "constructor.constructor"}) {
      for (Object d : new Object[] {0.5, -1e-300, 1e300, -0.0}) {
        String html = render(c, "s", s, "d", d, "big", new BigDecimal("-1E-30"), "flag", false);
        Matcher m = Pattern.compile(" [a-z:-]+=\"([^\"]*)\"").matcher(html);
        while (m.find()) {
          assertFalse(m.group(1).contains("&"), html);
          values.add(m.group(1));
        }
      }
    }
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(Context.VERSION_ECMASCRIPT);
    for (String v : values) {
      assertTrue(v.matches("/(?!/)([A-Za-z0-9._~/-]|%[0-9A-F]{2})*|-?(0|[1-9][0-9]*)(\\.[0-9]+)?|true|false"), v);
      assertFalse(v.contains("--"), v);
      for (String code : new String[] {v, "(" + v + ")", "x=" + v}) {
        AstRoot root;
        try {
          root = new Parser(env).parse(code, "v", 1);
        } catch (EvaluatorException notJavaScript) {
          continue;
        }
        boolean[] runs = new boolean[1];
        root.visit(n -> {
          runs[0] |= n instanceof FunctionCall || n instanceof UpdateExpression
              || n instanceof Assignment && !(code.startsWith("x=") && n.getParent() != null
                  && n.getParent().getParent() == root);
          return true;
        });
        assertFalse(runs[0], code);
      }
    }
  }

  /** Static values outside HTML stay unrestricted. */
  @Test
  void staticValuesAreUnrestricted() {
    compile("<p data-name=\"anything\" hx-on:click=\"go()\" x-data=\"{open: false}\">x</p>").assertSuccess();
  }
}
