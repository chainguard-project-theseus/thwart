package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #539: a plain computed value goes only into an inert-text attribute
 * ({@code inert-attributes.txt}); any other standard attribute without a more specific context
 * (TYPED_ATTRIBUTE) takes only literals and numbers (#565).
 */
class InertAttributesTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://tessera.dev\""
          + " t:params=\"String s, boolean b, int n, SafeHtml u, CssValue v, com.example.Kind k\">";

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
        "<p title=\"&quot;\" id=\"&quot;\" class=\"&quot;\" aria-label=\"&quot;\">x</p>"
            + "<input name=\"&quot;\" value=\"&quot;\" placeholder=\"&quot;\">",
        render("<p t:title=\"s\" t:id=\"s\" t:class=\"s\" t:aria-label=\"s\">x</p>"
            + "<input t:name=\"s\" t:value=\"s\" t:placeholder=\"s\">", "s", "\""));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "<a t:rel=\"s\">x</a>",
    "<a t:target=\"s\">x</a>",
    // A custom element has no exemption (#584).
    "<my-el t:target=\"s\"></my-el>",
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
    // The fix-it points at a JSON block, not a data-* attribute, which takes no String since #584.
    compile("\n" + body)
        .assertError(
            "t.html:2:",
            "is not allowed",
            "is not an inert-text attribute",
            "write a static attribute",
            "<script type=\"application/json\"> block");
  }

  @Test
  void literalsAndNumbersCompile() {
    assertEquals(
        "<a rel=\"nofollow\" target=\"_blank\">x</a>",
        render("<a t:rel=\"b ? 'noopener' : 'nofollow'\" t:target=\"'_blank'\">x</a>"));
    assertEquals(
        "<svg><rect x=\"3\" stroke-width=\"3px\"/><g transform=\"translate(3 0)\"></g></svg>",
        render("<svg><rect t:x=\"n\" t:stroke-width=\"|${n}px|\"/><g t:transform=\"|translate(${n} 0)|\"></g></svg>",
            "n", 3));
  }

  /** #565: a safe type is the wrong kind for a typed attribute, on HTML and on SVG or MathML. */
  @ParameterizedTest
  @ValueSource(strings = {
    "<iframe t:sandbox=\"u\"></iframe>",
    "<p t:itemtype=\"u\">x</p>",
    "<a t:rel=\"b ? u : 'nofollow'\">x</a>",
    "<svg><rect t:stroke-width=\"v\"/></svg>",
    "<svg><g t:transform=\"|scale(${v})|\"></g></svg>",
    "<math><mi t:mathcolor=\"v\">x</mi></math>",
  })
  void safeTypesAreRejected(String body) {
    compile("\n" + body).assertError("t.html:2:", "is not allowed", "only combine string literals and numbers");
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

  /** Vue shorthand bindings are not plain names, so they take no computed value at all (#584). */
  @Test
  void shorthandBindingsTakeNoValue() {
    compile("<a t::rel=\"s\">x</a>").assertError("t::rel is not allowed", "names must match");
    compile("<a t::title=\"s\">x</a>").assertError("t::title is not allowed", "names must match");
  }

  /**
   * A meta's content is inert text only as named metadata (#590): a static name on the allow-list
   * (description, keywords, author, application-name, generator, color-scheme, theme-color), and no
   * http-equiv, property or itemprop. Any other meta content takes literals only.
   */
  @Test
  void metaContentIsInertOnlyAsNamedMetadata() {
    assertEquals(
        "<meta name=\"description\" content=\"&quot;\"><meta NAME=\" Author \" content=\"&quot;\">",
        render("<meta name=\"description\" t:content=\"s\"><meta NAME=\" Author \" t:content=\"s\">", "s", "\""));
    assertEquals(
        "<meta name=\"application-name\" content=\"a\"><meta name=\"generator\" content=\"a\">"
            + "<meta name=\"color-scheme\" content=\"a\"><meta name=\"theme-color\" content=\"a\">",
        render("<meta name=\"application-name\" t:content=\"s\"><meta name=\"generator\" t:content=\"s\">"
            + "<meta name=\"color-scheme\" t:content=\"s\"><meta name=\"theme-color\" t:content=\"s\">", "s", "a"));
    assertEquals(
        "<meta name=\"referrer\" content=\"origin\"><meta name=\"viewport\" content=\"width=device-width\">",
        render("<meta name=\"referrer\" t:content=\"b ? 'no-referrer' : 'origin'\">"
            + "<meta name=\"viewport\" t:content=\"'width=device-width'\">"));
    assertEquals("<meta name=\"keywords\" content=\"a\">",
        render("<meta name=\"keywords\" t:attr=\"content=${s}\">", "s", "a"));
    // Names off the allow-list, and Open Graph, still take literals.
    assertEquals(
        "<meta name=\"apple-itunes-app\" content=\"app-id=1\"><meta property=\"og:title\" content=\"T\">",
        render("<meta name=\"apple-itunes-app\" t:content=\"'app-id=1'\"><meta property=\"og:title\" t:content=\"'T'\">"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    // #590: names a browser, OS or crawler acts on, and unknown names, are not on the allow-list.
    "<meta name=\"apple-itunes-app\" t:content=\"s\">",
    "<meta name=\"google-site-verification\" t:content=\"s\">",
    "<meta name=\"msapplication-config\" t:content=\"s\">",
    "<meta name=\"msapplication-TileImage\" t:content=\"s\">",
    "<meta name=\"og:image\" t:content=\"s\">",
    "<meta name=\"twitter:title\" t:content=\"s\">",
    "<meta name=\"refresh\" t:content=\"s\">",
    "<meta name=\"robots\" t:content=\"s\">",
    "<meta name=\"q\" t:content=\"s\">",
    "<meta name=\"\" t:content=\"s\">",
    "<meta name=\"apple-itunes-app\" t:attr=\"content=${s}\">",
    "<meta name=\"description\" name=\"apple-itunes-app\" t:content=\"s\">",
    // A computed name, with or without a static one.
    "<meta t:name=\"'description'\" t:content=\"s\">",
    "<meta t:attr=\"name='description', content=${s}\">",
    // property and itemprop are read by crawlers (og:url, al:ios:url) whatever the name says.
    "<meta property=\"og:title\" name=\"description\" t:content=\"s\">",
    "<meta name=\"description\" property=\"al:ios:url\" t:content=\"s\">",
    "<meta name=\"description\" itemprop=\"url\" t:content=\"s\">",
    "<meta name=\"description\" t:property=\"s\" t:content=\"s\">",
    "<meta name=\"description\" t:attr=\"itemprop=${s}\" t:content=\"s\">",
    "<meta name=\"twitter:image\" property=\"og:image\" t:content=\"s\">",
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
