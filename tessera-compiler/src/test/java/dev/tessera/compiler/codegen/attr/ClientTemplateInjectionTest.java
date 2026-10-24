package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Client-side template injection (#248): Vue, petite-vue and AngularJS compile the rendered DOM
 * as a template, so {@code {{ }}} in an escaped value would run as an expression. Element text
 * breaks every {@code {{} with an empty comment; HTML {@code <title>}/{@code <textarea>} (RCDATA),
 * attributes and scripts are unchanged; an expression inside a static {@code {{ }}} is a build
 * error.
 */
class ClientTemplateInjectionTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\""
          + " t:params=\"String s, String a, String b, SafeHtml h\">";
  private static final String PAYLOAD = "{{ constructor.constructor('alert(1)')() }}";
  private static final String BROKEN = "<!---->{<!---->{ constructor.constructor(&#39;alert(1)&#39;)() }}";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, String s) {
    return render(body, s, "", "");
  }

  private static String render(String body, String s, String a, String b) {
    String out =
        compile(body).assertSuccess().render("t", Map.of("s", s, "a", a, "b", b, "h", dev.tessera.runtime.SafeHtml.EMPTY));
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void issueReproductionIsBroken() {
    assertEquals(
        "<div id=\"app\" v-cloak>\n  <p>" + BROKEN + "</p>\n</div>",
        render("<div id=\"app\" v-cloak>\n  <p t:text=\"s\">...</p>\n</div>", PAYLOAD));
    assertEquals("<p>a " + BROKEN + " b</p>", render("<p>a [[s]] b</p>", PAYLOAD));
    // No directive needed: the mount point may be chosen in JavaScript or in a layout.
    assertEquals("<span>" + BROKEN + "</span>", render("<span t:text=\"s\">x</span>", PAYLOAD));
  }

  @Test
  void bracesNeverMeetAcrossStaticTextOrValues() {
    // static "{" then a value starting with "{"
    assertEquals("<p>{<!---->{x}}</p>", render("<p>{[[s]]</p>", "{x}}"));
    // a value ending with "{" then static "{"
    assertEquals("<p>a{<!---->{x}}</p>", render("<p>[[s]]{x}}</p>", "a{"));
    // two adjacent values
    assertEquals("<p><!---->{<!----><!---->{x}}</p>", render("<p>[[a]][[b]]</p>", "", "{", "{x}}"));
    assertEquals("<p>{<!---->{<!---->{x}}}</p>", render("<p>{[[s]]</p>", "{{x}}}"));
  }

  @Test
  void valuesWithoutBracesAreUnchanged() {
    assertEquals("<p>a &amp; b }} {</p>", render("<p>[[s]] {</p>", "a & b }}"));
    assertEquals("<p>{{ static }}</p>", render("<p>{{ static }}</p>", ""));
  }

  @Test
  void textInOtherHtmlElementsIsBroken() {
    // Comments are harmless in options, SVG and MathML text, and in raw-text fallbacks.
    assertEquals("<select><option><!---->{<!---->{x}}</option></select>",
        render("<select><option t:text=\"s\">o</option></select>", "{{x}}"));
    assertEquals("<svg><text><!---->{<!---->{x}}</text><desc><!---->{<!---->{x}}</desc></svg>",
        render("<svg><text t:text=\"s\">t</text><desc>[[s]]</desc></svg>", "{{x}}"));
    assertEquals("<math><mi><!---->{<!---->{x}}</mi></math>", render("<math><mi t:text=\"s\">x</mi></math>", "{{x}}"));
    assertEquals("<template><p><!---->{<!---->{x}}</p></template>",
        render("<template><p t:text=\"s\">x</p></template>", "{{x}}"));
  }

  @Test
  void titleAndTextareaAreNotChanged() {
    // RCDATA: a comment would show as text there (guide section 7).
    assertEquals("<title>{{x}} &amp;</title>", render("<title t:text=\"s\">t</title>", "{{x}} &"));
    assertEquals("<title>a {{x}} &amp;</title>", render("<title>a [[s]]</title>", "{{x}} &"));
    assertEquals("<textarea>{{x}}</textarea>", render("<textarea t:text=\"s\"></textarea>", "{{x}}"));
    assertEquals("<textarea>{{x}}</textarea>", render("<textarea>[[s]]</textarea>", "{{x}}"));
    assertEquals("<TITLE>{{x}}</TITLE>", render("<TITLE t:text=\"s\">t</TITLE>", "{{x}}"));
  }

  @Test
  void svgAndMathTitleAndTextareaTextIsBroken() {
    // #277: inside <svg>/<math> these are ordinary elements, not RCDATA: their text is element
    // text, which the frameworks interpolate.
    assertEquals("<svg><title><!---->{<!---->{x}}</title></svg>", render("<svg><title>[[s]]</title></svg>", "{{x}}"));
    assertEquals(
        "<svg><title><!---->{<!---->{x}}</title></svg>", render("<svg><title t:text=\"s\">t</title></svg>", "{{x}}"));
    assertEquals(
        "<svg><textarea><!---->{<!---->{x}}</textarea></svg>",
        render("<svg><textarea t:text=\"s\"></textarea></svg>", "{{x}}"));
    assertEquals(
        "<math><title><!---->{<!---->{x}}</title></math>", render("<math><title>[[s]]</title></math>", "{{x}}"));
    // An HTML textarea in an integration point is still RCDATA.
    assertEquals(
        "<svg><foreignObject><textarea>{{x}}</textarea></foreignObject></svg>",
        render("<svg><foreignObject><textarea>[[s]]</textarea></foreignObject></svg>", "{{x}}"));
  }

  @Test
  void attributesAndScriptsAreNotChanged() {
    assertEquals("<i title=\"{{x}}\" data-v=\"{{x}}\">i</i>", render("<i t:title=\"s\" t:data-v=\"s\">i</i>", "{{x}}"));
    assertEquals("<a href=\"{{x}}\">a</a>", render("<a t:href=\"s\">a</a>", "{{x}}"));
    assertEquals("<script>var v = \"{{x}}\";</script>", render("<script>var v = [[s]];</script>", "{{x}}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ greet('[[s]]') }}</p>",
        "<p>{{ f([[s]]) }}</p>",
        "<p>{{[[s]]}}</p>",
        "<p>{{ a }} {{ b + [[s]] }}</p>",
        "<p>&#123;&#123; [[s]] }}</p>",
        "<p>&#x7B;&lbrace; [[s]] }}</p>",
        "<p>{{ [(h)] }}</p>",
        "<title>{{ [[s]] }}</title>",
        "<textarea>{{ [[s]] }}</textarea>",
        "<svg><title>{{ [[s]] }}</title></svg>",
      })
  void expressionInsideStaticMustacheIsABuildError(String body) {
    compile(body).assertError("t.html:1:", "inside a client-side {{ ... }} interpolation", "data-*");
  }

  @Test
  void mustacheErrorNamesTheExpressionPosition() {
    compile("\n<p>\n  {{ greet('[[s]]') }}</p>").assertError("t.html:3:15:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ a }} [[s]] {{ b }}</p>",
        "<p>}} [[s]]</p>",
        "<p>{ { [[s]] }}</p>",
        "<p>{{ x &#125;} [[s]]</p>",
        "<p>&amp;#123;&amp;#123; [[s]]</p>",
        "<script>var t = '{{'; var v = [[s]];</script>",
        "<p>{{ a }}<b t:text=\"s\">x</b></p>",
      })
  void expressionOutsideStaticMustacheCompiles(String body) {
    compile(body).assertSuccess();
  }
}
