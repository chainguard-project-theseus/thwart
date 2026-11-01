package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code v-pre} on a {@code <title>} or {@code <textarea>} with computed content (#549). In RCDATA
 * a comment is text, so a value's {@code {{} can't be broken; Vue 3 (in a mounted region) and
 * petite-vue (mounted on the document without {@code v-scope}) interpolate it, and run
 * {@code {{$emit.constructor('...')()}}} as code. Both skip an element under {@code v-pre}
 * (checked in Chromium with Vue 3.5.44 and petite-vue 0.4.1).
 */
class VPreRcdataTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">";
  private static final String PAYLOAD = "{{$emit.constructor('alert(1)')()}}";
  private static final String WRITTEN = "{{$emit.constructor(&#39;alert(1)&#39;)()}}";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, String s) {
    Compilation c = compile(body).assertSuccess();
    String out = c.render("t", Map.of("s", s));
    c.assertInSkeleton("t", out);
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void hostileValueIsWrittenUnderVPre() {
    assertEquals("<textarea v-pre>\n" + WRITTEN + "</textarea>", render("<textarea t:text=\"s\">x</textarea>", PAYLOAD));
    assertEquals("<textarea v-pre>\n" + WRITTEN + "</textarea>", render("<textarea>[[s]]</textarea>", PAYLOAD));
    assertEquals("<textarea rows=\"3\" v-pre>a " + WRITTEN + "</textarea>", render("<textarea rows=\"3\">a [[s]]</textarea>", PAYLOAD));
    assertEquals("<title v-pre>" + WRITTEN + "</title>", render("<title t:text=\"s\">t</title>", PAYLOAD));
    assertEquals("<title v-pre>Home - " + WRITTEN + "</title>", render("<title>Home - [[s]]</title>", PAYLOAD));
    assertEquals("<title class=\"a\" v-pre>" + WRITTEN + "</title>", render("<title class=\"a\" t:text=\"s\">t</title>", PAYLOAD));
    // Inside t:if, and with a value that may be empty: the start tag still has it.
    assertEquals("<title v-pre></title>", render("<title t:if=\"true\">[[s]]</title>", ""));
  }

  // t:field on a textarea: FormsTest.textareaBody and the other form tests.

  @Test
  void theVPreIsInTheSkeleton() {
    Compilation c = compile("<textarea>[[s]]</textarea>").assertSuccess();
    assertTrue(c.skeleton("t").toString().contains("\"<textarea\" \" v-pre\" \">\""), c.skeleton("t").toString());
  }

  @Test
  void staticContentGetsNoVPre() {
    assertEquals("<textarea>a</textarea><title>b</title><textarea rows=\"2\"></textarea>",
        render("<textarea>a</textarea><title>b</title><textarea rows=\"2\"></textarea>", ""));
    assertEquals("<p>" + "<!---->{<!---->{<!---->x<!---->}<!---->}<!---->" + "</p>", render("<p>[[s]]</p>", "{{x}}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"v-pre", "V-PRE", "v-pre=\"\""})
  void aStaticVPreIsNotRepeated(String attribute) {
    String out = render("<textarea " + attribute + " t:text=\"s\">x</textarea>", PAYLOAD);
    assertEquals("<textarea " + attribute + ">\n" + WRITTEN + "</textarea>", out);
  }

  @ParameterizedTest
  @ValueSource(strings = {"v-model=\"text\"", ":rows=\"n\"", "@input=\"f\"", "v-cloak", "v-bind:rows=\"n\"", "V-MODEL=\"text\""})
  void aVueDirectiveBesideComputedContentIsAnError(String directive) {
    String name = directive.contains("=") ? directive.substring(0, directive.indexOf('=')) : directive;
    compile("\n<textarea " + directive + " t:text=\"s\">x</textarea>")
        .assertError("t.html:2:", name + " on a <textarea> with computed content is not supported",
            "Tessera adds v-pre there", "drop " + name);
    compile("\n<title " + directive + ">[[s]]</title>")
        .assertError("t.html:2:", name + " on a <title> with computed content is not supported");
  }

  @Test
  void aComputedJsonBindingIsAnErrorToo() {
    compile("<textarea t:attr=\"v-bind:rows=${s.length()}\" t:text=\"s\">x</textarea>")
        .assertError("t.html:1:", "v-bind:rows on a <textarea> with computed content is not supported");
  }

  @Test
  void vueDirectivesWithStaticContentAreUnchanged() {
    assertEquals("<textarea v-model=\"text\"></textarea>", render("<textarea v-model=\"text\"></textarea>", ""));
  }

  @Test
  void alpineDirectivesAreKept() {
    // Alpine ignores v-pre, so x-model keeps working.
    assertEquals("<textarea x-model=\"text\" v-pre>\n" + WRITTEN + "</textarea>",
        render("<textarea x-model=\"text\" t:text=\"s\">x</textarea>", PAYLOAD));
  }

  @Test
  void aStaticInterpolationBesideAValueIsAnError() {
    // One coarse rule (#571): no static brace in a <title> or <textarea>, with or without a value.
    String rule = "in the static text of a <title> or <textarea> is not supported: Tessera adds v-pre there";
    compile("\n<title>{{ app }} - [[s]]</title>").assertError("t.html:2:8:", "'{' " + rule);
    compile("<textarea>[[s]] {{ x }}</textarea>").assertError("t.html:1:", rule);
    // In SVG a title has child elements: static text inside a condition or switch counts.
    compile("<svg><title><t:block t:if=\"true\">{{ app }}</t:block> [[s]]</title></svg>").assertError("t.html:1:", rule);
    compile("<math><textarea><t:block t:switch=\"s\"><t:block t:case=\"'a'\">{{ a }}</t:block></t:block>[[s]]</textarea></math>")
        .assertError("t.html:1:", rule);
    compile("<textarea>{[[s]]{</textarea>").assertError("t.html:1:", rule);
    compile("<title>{{ b }}</title>").assertError("t.html:1:", rule);
    // Not even under a static v-pre (#571): the rule looks at the text alone.
    compile("<textarea v-pre>{{ a }}</textarea>").assertError("t.html:1:", rule);
  }
}
