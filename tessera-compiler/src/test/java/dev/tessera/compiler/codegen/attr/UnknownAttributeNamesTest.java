package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deny-by-default names for computed attributes (#330, security-architecture.md section 5.6): a
 * {@code t:<name>} or {@code t:attr} entry must be a standard HTML, SVG, MathML or ARIA attribute,
 * {@code data-*}, a plain prop on a custom element, or a framework name Contexts classifies. Any
 * other name is a build error naming file, line and column, with a {@code data-*} fix-it. Static
 * attributes are copied verbatim.
 */
class UnknownAttributeNamesTest {

  private static final String HEADER = "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  /** Unpoly's up-on-* (#273), htmx 4's hx-action/hx-method (#276), and names no spec defines. */
  @ParameterizedTest
  @ValueSource(strings = {"up-on-loaded", "up-on-finished", "up-href", "hx-action", "hx-method", "foo", "aria-foo",
      "UP-ON-LOADED", "xml:lang"})
  void unknownNamesAreErrors(String name) {
    compile("<p t:" + name + "=\"s\">x</p>")
        .assertError("t.html:1:", "t:" + name + " is not allowed", "'" + name + "' is not a known attribute",
            "use data-" + name.toLowerCase(java.util.Locale.ROOT));
    compile("<p t:attr=\"" + name + "=s\">x</p>")
        .assertError("t.html:1:", "t:attr " + name + " is not allowed", "is not a known attribute");
  }

  @Test
  void errorNamesLineAndColumn() {
    compile("\n  <p t:up-on-loaded=\"s\">x</p>").assertError("t.html:2:6:", "t:up-on-loaded is not allowed");
  }

  /** {@code on*} stays an event handler, whatever the rest of the name. */
  @Test
  void unknownEventHandlerIsAnError() {
    compile("<p t:attr=\"onfoo=${s}\">x</p>").assertError("t.html:1:", "t:attr onfoo is not allowed", "event-handler");
    compile("<p t:onfoo=\"s\">x</p>").assertError("t.html:1:", "t:onfoo is not allowed", "event-handler");
  }

  /** A custom element's props are plain names; a {@code -} in one, or a plain name on a native element, is not. */
  @Test
  void customElementProps() {
    compile("<my-el t:my-prop=\"s\"></my-el>").assertError("t.html:1:", "t:my-prop is not allowed");
    compile("<p t:prop=\"s\">x</p>").assertError("t.html:1:", "t:prop is not allowed");
    compile("<svg><font-face t:prop=\"s\"></font-face></svg>").assertError("t.html:1:", "t:prop is not allowed");
  }

  @Test
  void knownNamesCompile() {
    Compilation c =
        compile("<p t:data-x=\"s\" t:aria-label=\"s\" t:role=\"s\" t:data-up-on-loaded=\"s\">x</p>"
                + "<svg><path t:stroke-dasharray=\"s\" t:d=\"s\"/></svg>"
                + "<my-el t:prop=\"s\" t:title=\"s\"></my-el>"
                + "<font t:color=\"s\" t:face=\"s\">x</font>"
                + "<math><mi t:mathvariant=\"s\">x</mi></math>"
                + "<p t:attr=\"data-y=${s}, aria-hidden=${s}\">x</p>")
            .assertSuccess();
    assertEquals(
        "<div><p data-x=\"&quot;\" aria-label=\"&quot;\" role=\"&quot;\" data-up-on-loaded=\"&quot;\">x</p>"
            + "<svg><path stroke-dasharray=\"&quot;\" d=\"&quot;\"/></svg>"
            + "<my-el prop=\"&quot;\" title=\"&quot;\"></my-el>"
            + "<font color=\"&quot;\" face=\"&quot;\">x</font>"
            + "<math><mi mathvariant=\"&quot;\">x</mi></math>"
            + "<p data-y=\"&quot;\" aria-hidden=\"&quot;\">x</p></div>",
        c.render("t", Map.of("s", "\"")));
  }

  /** Static attributes are not checked: they are copied verbatim. */
  @Test
  void staticNamesAreUnchanged() {
    String markup = "<p up-on-loaded=\"go()\" hx-action=\"/x\" foo=\"bar\">x</p>";
    assertEquals("<div>" + markup + "</div>", compile(markup).assertSuccess().render("t", Map.of("s", "x")));
  }
}
