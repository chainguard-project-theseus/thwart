package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.CssValue;
import dev.tessera.runtime.TrustedUrl;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #539: a plain computed value goes only into an inert-text attribute
 * ({@code inert-attributes.txt}); any other standard attribute without a more specific context
 * (TYPED_ATTRIBUTE) takes only literals, numbers and a safe type.
 */
class InertAttributesTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\""
          + " t:params=\"String s, boolean b, int n, TrustedUrl u, CssValue v, com.example.Kind k\">";

  private static final String KIND = "package com.example; public enum Kind { A, B }";

  private static Compilation compile(String body) {
    return Compilation.builder().java("com.example.Kind", KIND).template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, Object... kv) {
    Map<String, Object> m = new HashMap<>(Map.of("b", false, "n", 0));
    m.putAll(Compilation.model(kv));
    String out = compile(body).assertSuccess().render("t", m);
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  @Test
  void inertTextTakesAString() {
    assertEquals(
        "<p title=\"&quot;\" id=\"&quot;\" class=\"&quot;\" aria-label=\"&quot;\" data-x=\"&quot;\">x</p>"
            + "<input name=\"&quot;\" value=\"&quot;\" placeholder=\"&quot;\"><my-el target=\"&quot;\"></my-el>",
        render("<p t:title=\"s\" t:id=\"s\" t:class=\"s\" t:aria-label=\"s\" t:data-x=\"s\">x</p>"
            + "<input t:name=\"s\" t:value=\"s\" t:placeholder=\"s\"><my-el t:target=\"s\"></my-el>", "s", "\""));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "<a t:rel=\"s\">x</a>",
    "<a t:target=\"s\">x</a>",
    "<iframe t:sandbox=\"s\"></iframe>",
    "<iframe t:allow=\"s\"></iframe>",
    "<img t:crossorigin=\"s\">",
    "<p t:itemtype=\"s\">x</p>",
    // A browser may load a <param name=src> value as the object's resource URL.
    "<object type=\"application/pdf\"><param name=\"src\" t:value=\"s\"></object>",
    "<a t:attr=\"rel=${s}\">x</a>",
    "<a t:rel=\"k\">x</a>",
    "<svg><rect t:x=\"s\"/></svg>",
    "<svg><g t:transform=\"s\"></g></svg>",
    "<svg><rect t:stroke-width=\"s\"/></svg>",
    "<math><mi t:mathcolor=\"s\">x</mi></math>",
  })
  void otherStandardAttributesRejectPlainValues(String body) {
    compile("\n" + body).assertError("t.html:2:", "is not allowed", "is not an inert-text attribute", "write a static attribute");
  }

  @Test
  void literalsNumbersAndSafeTypesCompile() {
    assertEquals(
        "<a rel=\"nofollow\" target=\"_blank\">x</a>",
        render("<a t:rel=\"b ? 'noopener' : 'nofollow'\" t:target=\"'_blank'\">x</a>"));
    assertEquals("<p itemtype=\"https://schema.org/Person\">x</p>",
        render("<p t:itemtype=\"u\">x</p>", "u", TrustedUrl.unchecked("https://schema.org/Person")));
    assertEquals(
        "<svg><rect x=\"3\" stroke-width=\"2px\"/><g transform=\"translate(3 0)\"></g></svg>",
        render("<svg><rect t:x=\"n\" t:stroke-width=\"v\"/><g t:transform=\"|translate(${n} 0)|\"></g></svg>",
            "n", 3, "v", CssValue.length("2px")));
    // A safe type of the other namespace does not count.
    compile("<svg><rect t:x=\"u\"/></svg>").assertError("t:x is not allowed", "CssValues");
    compile("<a t:rel=\"v\">x</a>").assertError("t:rel is not allowed", "TrustedUrls");
  }

  /**
   * A boolean attribute that is not inert text (declare on HTML; hidden, disabled on SVG, where
   * the inert scope is html) keeps the condition handling: present or absent, never a value.
   */
  @Test
  void typedBooleanAttributesTakeACondition() {
    String body = "<object t:declare=\"b\"></object><svg><rect t:hidden=\"b\" t:disabled=\"!b\"/></svg>";
    assertEquals("<object declare=\"declare\"></object><svg><rect hidden=\"hidden\"/></svg>", render(body, "b", true));
    assertEquals("<object></object><svg><rect disabled=\"disabled\"/></svg>", render(body, "b", false));
    // A String next to it, in a non-boolean typed attribute, still fails.
    compile("<object t:declare=\"b\"></object>\n<iframe t:sandbox=\"s\"></iframe>")
        .assertError("t.html:2:", "t:sandbox is not allowed", "is not an inert-text attribute");
  }

  /** A JSON binding's target must be an inert-text attribute too. */
  @Test
  void bindTargetsMustBeInert() {
    compile("<a t::rel=\"s\">x</a>").assertError("t::rel is not allowed", "Vue or Alpine binding");
    render("<a t::title=\"s\">x</a>", "s", "x");
  }

  /**
   * A meta's content is inert text only as named metadata: a static name other than referrer or
   * viewport, and no http-equiv.
   */
  @Test
  void metaContentIsInertOnlyAsNamedMetadata() {
    assertEquals(
        "<meta name=\"description\" content=\"&quot;\"><meta name=\"author\" property=\"x\" content=\"&quot;\">",
        render("<meta name=\"description\" t:content=\"s\"><meta name=\"author\" property=\"x\" t:content=\"s\">",
            "s", "\""));
    assertEquals(
        "<meta name=\"referrer\" content=\"origin\"><meta name=\"viewport\" content=\"width=device-width\">",
        render("<meta name=\"referrer\" t:content=\"b ? 'no-referrer' : 'origin'\">"
            + "<meta name=\"viewport\" t:content=\"'width=device-width'\">"));
    assertEquals("<meta name=\"keywords\" content=\"a\">",
        render("<meta name=\"keywords\" t:attr=\"content=${s}\">", "s", "a"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "<meta name=\"referrer\" t:content=\"s\">",
    "<meta NAME=\" Viewport \" t:content=\"s\">",
    // htmx merges it into htmx.config: {"selfRequestsOnly":false,"allowEval":true}
    "<meta name=\"htmx-config\" t:content=\"s\">",
    "<meta http-equiv=\"content-security-policy\" t:content=\"s\">",
    "<meta http-equiv=\"default-style\" t:content=\"s\">",
    "<meta name=\"description\" http-equiv=\"default-style\" t:content=\"s\">",
    "<meta property=\"og:title\" t:content=\"s\">",
    "<meta t:content=\"s\">",
    "<meta name=\"referrer\" t:attr=\"content=${s}\">",
    // A computed name could replace the static one: name=referrer with a plain content.
    "<meta name=\"description\" t:name=\"'referrer'\" t:content=\"s\">",
    "<meta name=\"description\" t:attr=\"name='referrer'\" t:content=\"s\">",
    "<meta name=\"description\" t:attr=\"name='referrer', content=${s}\">",
    "<meta name=\"description\" t:attrappend=\"name=${s}\" t:content=\"s\">",
    "<p t:content=\"s\">x</p>",
  })
  void otherMetaContentRejectsPlainValues(String body) {
    compile("\n" + body).assertError("t.html:2:", "is not allowed", "is not an inert-text attribute", "write a static attribute");
  }
}
