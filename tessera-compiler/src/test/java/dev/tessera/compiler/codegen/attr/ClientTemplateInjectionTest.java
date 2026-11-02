package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Client-side template injection is outside the guarantee: client frameworks
 * that compile the server's DOM as a template are unsupported, and a strict CSP is the backstop.
 * A value is escaped as plain text, the same in element text, RCDATA and attributes: {@code {{}
 * renders as {@code {{}, no comment breaks it and no {@code v-pre} is added. Static braces are
 * ordinary text.
 */
class ClientTemplateInjectionTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\""
          + " t:params=\"String s, String a, String b, SafeHtml h\">";
  private static final String PAYLOAD = "{{ constructor.constructor('alert(1)')() }}";
  private static final String ESCAPED = "{{ constructor.constructor(&#39;alert(1)&#39;)() }}";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, String s) {
    String out =
        compile(body).assertSuccess().render("t", Map.of("s", s, "a", "", "b", "", "h", dev.tessera.runtime.SafeHtml.EMPTY));
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void bracesAreOrdinaryEscapedText() {
    // Element text.
    assertEquals("<p>a " + ESCAPED + " b</p>", render("<p>a [[s]] b</p>", PAYLOAD));
    assertEquals("<span>" + ESCAPED + "</span>", render("<span t:text=\"s\">x</span>", PAYLOAD));
    assertEquals("<p>{{ x }}}}</p>", render("<p>{{ x }}[[s]]</p>", "}}"));
    // RCDATA.
    assertEquals("<title>{{x}} &amp;</title>", render("<title t:text=\"s\">t</title>", "{{x}} &"));
    assertEquals("<title>a {{x}}</title>", render("<title>a [[s]]</title>", "{{x}}"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea t:text=\"s\"></textarea>", "{{x}}"));
    assertEquals("<textarea>\n{{x}}</textarea>", render("<textarea>[[s]]</textarea>", "{{x}}"));
    // Attributes.
    assertEquals("<i title=\"{{x}}\" id=\"{{x}}\">i</i>", render("<i t:title=\"s\" t:id=\"s\">i</i>", "{{x}}"));
    assertEquals("<a href=\"{{x}}\">a</a>", render("<a t:href=\"s\">a</a>", "{{x}}"));
    // Script: the JavaScript string escaper's usual output.
    assertEquals("<script>var v = (\"\\u007b\\u007bx}}\");</script>", render("<script>var v = [[s]];</script>", "{{x}}"));
  }

  /** Static braces anywhere are the author's text, formerly rejected by the static-brace rule (#571). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<p>{{ greet('[[s]]') }}</p>",
        "<p>{{</p>",
        "<p>}} [[s]]</p>",
        "<p>{[[s]]{ [[a]] }}</p>",
        "<title>{{ [[s]] }}</title>",
        "<textarea>{{</textarea>",
        "<p v-pre>{{ [[s]] }}</p>",
        "<script>var v = [[s]]; var t = '{{';</script>",
      })
  void staticBracesCompile(String body) {
    compile(body).assertSuccess();
  }
}
