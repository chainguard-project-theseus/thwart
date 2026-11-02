package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #539, #761: a plain computed value goes only into an inert-text attribute on the closed allow-list
 * ({@code Contexts}); a listed attribute without a more specific context
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
    "<a t:target=\"s\">x</a>",
    // A custom element has no exemption (#584).
    "<my-el t:target=\"s\"></my-el>",
    // A browser may load a <param name=src> value as the object's resource URL.
    "<object type=\"application/pdf\"><param name=\"src\" t:value=\"s\"></object>",
    "<a t:attr=\"target=${s}\">x</a>",
    "<a t:target=\"k\">x</a>",
  })
  void typedAttributesRejectPlainValues(String body) {
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
        "<a target=\"_blank\">x</a><a target=\"_self\">y</a>",
        render("<a t:target=\"'_blank'\">x</a><a t:target=\"b ? '_blank' : '_self'\">y</a>"));
  }

  /** #565: a safe type is the wrong kind for a typed attribute. */
  @ParameterizedTest
  @ValueSource(strings = {
    "<a t:target=\"u\">x</a>",
    "<a t:target=\"b ? u : '_self'\">x</a>",
  })
  void safeTypesAreRejected(String body) {
    compile("\n" + body).assertError("t.html:2:", "is not allowed", "only combine string literals and numbers");
  }

  /** Vue shorthand bindings are not plain names, so they take no computed value at all (#584). */
  @Test
  void shorthandBindingsTakeNoValue() {
    compile("<a t::rel=\"s\">x</a>").assertError("t::rel is not allowed", "names must match");
    compile("<a t::title=\"s\">x</a>").assertError("t::title is not allowed", "names must match");
  }

  /**
   * #761: a standard name off the closed allow-list takes no computed value, a literal neither:
   * what was TYPED_ATTRIBUTE (rel, sandbox, allow, crossorigin, itemtype, declare) and every
   * {@code <meta>} content but a refresh, named metadata (#590) included.
   */
  @ParameterizedTest
  @ValueSource(strings = {
    "<a t:rel=\"s\">x</a>",
    "<a t:rel=\"b ? 'noopener' : 'nofollow'\">x</a>",
    "<a t:attr=\"rel=${s}\">x</a>",
    "<iframe t:sandbox=\"s\"></iframe>",
    "<iframe t:allow=\"s\"></iframe>",
    "<img t:crossorigin=\"s\">",
    "<p t:itemtype=\"s\">x</p>",
    "<object t:declare=\"b\"></object>",
    "<button t:type=\"'submit'\">x</button>",
    "<img t:loading=\"'lazy'\">",
    "<meta name=\"description\" t:content=\"s\">",
    "<meta NAME=\" Author \" t:content=\"s\">",
    "<meta name=\"keywords\" t:attr=\"content=${s}\">",
    "<meta name=\"viewport\" t:content=\"'width=device-width'\">",
    "<meta name=\"referrer\" t:content=\"b ? 'no-referrer' : 'origin'\">",
    "<meta property=\"og:title\" t:content=\"'T'\">",
    "<meta name=\"apple-itunes-app\" t:content=\"s\">",
    "<meta name=\"htmx-config\" t:content=\"s\">",
    "<meta http-equiv=\"content-security-policy\" t:content=\"s\">",
    "<meta http-equiv=\"default-style\" t:content=\"s\">",
    "<meta t:name=\"'description'\" t:content=\"s\">",
    "<meta t:attr=\"name='description', content=${s}\">",
    "<meta name=\"description\" t:property=\"s\">",
    "<meta t:content=\"s\">",
    "<p t:content=\"s\">x</p>",
  })
  void namesOffTheListAreRejected(String body) {
    compile("\n" + body).assertError("t.html:2:", "is not allowed", "is not on Tessera's list of computed attributes",
        "write it as a static value, or one element per value under t:if");
  }
}
