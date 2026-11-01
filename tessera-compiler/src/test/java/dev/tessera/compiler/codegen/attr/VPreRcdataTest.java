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

  /**
   * #586: the v-pre is one constant attribute, added whatever else the element has. A Vue
   * directive beside it is no longer checked: v-pre turns it off, which is the author's concern,
   * not a security one.
   */
  @ParameterizedTest
  @ValueSource(strings = {"v-model=\"text\"", ":rows=\"n\"", "@input=\"f\"", "v-cloak", "v-bind:rows=\"n\"", "V-MODEL=\"text\""})
  void aVueDirectiveBesideComputedContentGetsTheVPreToo(String directive) {
    assertEquals("<textarea " + directive + " v-pre>\n" + WRITTEN + "</textarea>",
        render("<textarea " + directive + " t:text=\"s\">x</textarea>", PAYLOAD));
    assertEquals("<title " + directive + " v-pre>" + WRITTEN + "</title>", render("<title " + directive + ">[[s]]</title>", PAYLOAD));
  }

  /**
   * #586: a computed binding beside computed content builds too, and takes what any other
   * non-HTML name takes (#584, NEUTRAL): a number, a boolean or a same-origin path, never a
   * String. Under v-pre, Vue 3 keeps the element's directives as plain attributes and petite-vue
   * skips the element, so the value is inert either way.
   */
  @Test
  void aComputedBindingBesideComputedContentGetsTheVPre() {
    String hostile = "\"}}{{constructor.constructor('alert(1)')()}}";
    String text = "&quot;}}{{constructor.constructor(&#39;alert(1)&#39;)()}}";
    int n = hostile.length();
    assertEquals("<textarea v-bind:rows=\"" + n + "\" v-pre>\n" + text + "</textarea>",
        render("<textarea t:attr=\"v-bind:rows=${s.length()}\" t:text=\"s\">x</textarea>", hostile));
    assertEquals("<title v-bind:lang=\"" + n + "\" v-pre>" + text + "</title>",
        render("<title t:attr=\"v-bind:lang=${s.length()}\">[[s]]</title>", hostile));
    // Negative: the hostile String itself can't reach the binding.
    compile("<textarea t:attr=\"v-bind:rows=${s}\" t:text=\"s\">x</textarea>").assertError("t.html:1:", "framework-neutral");
    compile("<title t:attr=\"v-bind:lang=${s}\">[[s]]</title>").assertError("t.html:1:", "framework-neutral");
  }

  /**
   * Only a static v-pre suppresses the generated one ({@code TemplateGenerator.addsVPre}); a
   * computed v-pre is a code attribute and fails to build, so no element ends up with two or none.
   */
  @Test
  void aComputedVPreIsAnError() {
    compile("<textarea t:attr=\"v-pre=${s}\" t:text=\"s\">x</textarea>")
        .assertError("t.html:1:", "t:attr v-pre is not allowed");
    compile("<title t:attr=\"v-pre=${s}\">[[s]]</title>").assertError("t.html:1:", "t:attr v-pre is not allowed");
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

  /**
   * #586: static text in a {@code <title>} or {@code <textarea>} follows the element-text brace rule
   * (#571): a complete {@code {{ ... }}} in one literal is the author's own; any other static brace is
   * an error, so no static brace can meet a value's.
   */
  @Test
  void staticTextFollowsTheElementTextBraceRule() {
    String rule = "outside a complete {{ ... }} is not supported in element text";
    compile("\n<title>{[[s]]}</title>").assertError("t.html:2:8:", "'{' " + rule);
    compile("<textarea>{[[s]]{</textarea>").assertError("t.html:1:", rule);
    compile("<textarea>[[s]] }</textarea>").assertError("t.html:1:", rule);
    compile("<title>{{ a [[s]] }}</title>").assertError("t.html:1:", rule);
    compile("<svg><title><t:block t:if=\"true\">{</t:block>[[s]]</title></svg>").assertError("t.html:1:", rule);
    // Positive controls: a complete static {{ ... }} beside a value is shown as text under the v-pre.
    assertEquals("<title v-pre>{{ app }} - " + WRITTEN + "</title>", render("<title>{{ app }} - [[s]]</title>", PAYLOAD));
    assertEquals("<textarea v-pre>\n" + WRITTEN + " {{ x }}</textarea>", render("<textarea>[[s]] {{ x }}</textarea>", PAYLOAD));
    assertEquals("<title>{{ b }}</title>", render("<title>{{ b }}</title>", ""));
    assertEquals("<textarea v-pre>{{ a }}</textarea>", render("<textarea v-pre>{{ a }}</textarea>", ""));
  }
}
