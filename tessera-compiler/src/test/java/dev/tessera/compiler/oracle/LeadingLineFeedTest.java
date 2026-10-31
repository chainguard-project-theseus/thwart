package dev.tessera.compiler.oracle;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.SafeHtml;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * A browser drops a line feed right after a {@code <pre>}, {@code <listing>} or {@code <textarea>}
 * start tag. When the content starts with a value, the compiler writes a line feed there first,
 * so the value and a static line feed after it reach the DOM exactly, whatever the value; checked
 * here with the spec parser on every render.
 */
class LeadingLineFeedTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String[] VALUES = {"", "a", "\nz"};

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("t.html", "<div " + NS + " t:import=\"java.util.List\" t:params=\"String s, boolean c, List<String> l\">"
            + body + "</div>")
        .compile()
        .assertSuccess();
  }

  private static String render(Compilation c, String s) {
    return render(c, s, List.of(s));
  }

  private static String render(Compilation c, String s, List<String> l) {
    return c.render("t", model("s", s, "c", true, "l", l));
  }

  /** The start tag of {@code element} with computed content: a textarea gets v-pre (#549). */
  private static String start(String element) {
    return element.equals("textarea") ? "<textarea v-pre>" : "<" + element + ">";
  }

  /** The text content of the first {@code element} in {@code html}, as the spec parser builds it. */
  private static String dom(String html, String element) {
    Document d = SpecParsers.named(SpecParsers.VNU_26).parse(html, true);
    return ((Element) d.getElementsByTagName(element).item(0)).getTextContent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"pre", "listing", "textarea"})
  void aValueFirstKeepsItsLineFeedAndTheStaticOneAfterIt(String element) {
    Compilation c = compile("<" + element + ">[[${s}]]\nx</" + element + ">");
    for (String s : VALUES) {
      String html = render(c, s);
      assertEquals(s + "\nx", dom(html, element), html);
    }
    assertEquals("<div>" + start(element) + "\n\nx</" + element + "></div>", render(c, ""));
    assertEquals("<div>" + start(element) + "\na\nx</" + element + "></div>", render(c, "a"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pre", "listing", "textarea"})
  void textAttributeKeepsTheValueExactly(String element) {
    Compilation c = compile("<" + element + " t:text=\"${s}\">placeholder</" + element + ">");
    for (String s : VALUES) {
      String html = render(c, s);
      assertEquals(s, dom(html, element), html);
    }
    assertEquals("<div>" + start(element) + "\n\nz</" + element + "></div>", render(c, "\nz"));
  }

  @Test
  void aConditionOrALoopFirstIsAValueFirst() {
    Compilation c = compile("<pre><t:block t:if=\"${c}\">[[${s}]]</t:block>\nx</pre>"
        + "<listing><t:block t:each=\"i : ${l}\">[[${i}]]</t:block>\nx</listing>");
    for (String s : VALUES) {
      String html = render(c, s);
      assertEquals(s + "\nx", dom(html, "pre"), html);
      assertEquals(s + "\nx", dom(html, "listing"), html);
    }
    // No item: the static line feed shows, as written (Thymeleaf's output would lose it).
    String html = render(c, "", List.of());
    assertEquals("\nx", dom(html, "listing"), html);
  }

  @Test
  void insertedHtmlFirstKeepsItsLineFeed() {
    Compilation c = Compilation.builder()
        .template("t.html", "<div " + NS + " t:import=\"dev.tessera.runtime.Fragment, dev.tessera.runtime.SafeHtml\""
            + " t:params=\"Fragment f, SafeHtml h\"><pre t:insert=\"${f}\">p</pre><listing t:utext=\"${h}\">p</listing></div>")
        .compile()
        .assertSuccess();
    String html = c.render("t", model("f", (Fragment) (ctx, out) -> out.write("\nz"), "h", SafeHtml.unchecked("\n<b>y</b>")));
    assertEquals("<div><pre>\n\nz</pre><listing>\n\n<b>y</b></listing></div>", html);
    assertEquals("\nz", dom(html, "pre"), html);
    assertEquals("\ny", dom(html, "listing"), html);
  }

  @Test
  void htmlElementsInOtherPlacesGetTheLineFeed() {
    // An HTML integration point, upper-case tag names, and <noscript>, which is markup with scripting off.
    Compilation c = compile("<svg><foreignObject><textarea>[[${s}]]\nx</textarea></foreignObject></svg>"
        + "<PRE>[[${s}]]\nx</PRE><noscript><listing>[[${s}]]\nx</listing></noscript>");
    for (String s : VALUES) {
      String html = render(c, s);
      assertEquals(s + "\nx", dom(html, "textarea"), html);
      assertEquals(s + "\nx", dom(html, "pre"), html);
      Document off = SpecParsers.named(SpecParsers.VNU_26).parse(html, false);
      assertEquals(s + "\nx", ((Element) off.getElementsByTagName("listing").item(0)).getTextContent(), html);
    }
  }

  @Test
  void anOmittedTagGetsNoLineFeed() {
    Compilation c = compile("<span><pre t:remove=\"tag\">[[${s}]]</pre></span><pre t:remove=\"body\">[[${s}]]</pre>");
    assertEquals("<div><span>a</span><pre></pre></div>", render(c, "a"));
  }

  @Test
  void staticContentIsWrittenAsIs() {
    Compilation c = compile("<pre>\nx [[${s}]]\n</pre><pre>y</pre><pre><b>[[${s}]]</b>\nx</pre><textarea></textarea>"
        + "<textarea>\n[[${s}]]</textarea>");
    assertEquals("<div><pre>\nx a\n</pre><pre>y</pre><pre><b>a</b>\nx</pre><textarea></textarea>"
        + "<textarea v-pre>\na</textarea></div>", render(c, "a"));
  }

  @Test
  void anSvgTextareaGetsNoLineFeed() {
    // Inside <svg> a <textarea> is an SVG element, which drops nothing.
    Compilation c = compile("<svg><textarea>[[${s}]]\nx</textarea></svg>");
    assertEquals("<div><svg><textarea v-pre>\nz\nx</textarea></svg></div>", render(c, "\nz"));
  }
}
