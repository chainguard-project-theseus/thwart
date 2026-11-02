package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * An inlined value right after a static {@code &} would finish a character reference: the text
 * escaper encodes {@code &} in a value but not {@code #} or letters, so {@code &[[s]]} with
 * {@code s = "#123;"} would render {@code &#123;}, which a browser decodes to {@code {}. Two of
 * them make a {@code {{} that Vue or AngularJS run. The verifier rejects every such hole
 * ({@code TEXT_NOT_IN_TEXT}): its parser is inside a character reference there, in element text,
 * RCDATA and foreign content alike, also when the {@code &} and the hole are in different nodes.
 * Attribute values can't have the problem: a computed attribute is one value through one escaper,
 * so a static {@code &} in it is encoded as {@code &amp;} too. An HTML message the build did not
 * check is printed as HTML only without a {@code &} the escapers did not write.
 */
class ValueAfterAmpersandTest {

  private static final String CHARREF =
      "a browser reads this value as part of a character reference, after a literal '&', not as text;"
          + " write the literal '&' before it as &amp; [verifier:TEXT_NOT_IN_TEXT]";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"String s, String a, String b\">\n" + body + "\n</div>")
        .compile();
  }

  private static String render(String body, String s) {
    String out = compile(body).assertSuccess().render("page", Map.of("s", s, "a", s, "b", s));
    return out.substring("<div>\n".length(), out.length() - "\n</div>".length());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // s = "#123;" twice: "&#123;&#123;", a {{
        "<p>&[[a]]&[[b]]</p>|7",
        // the value finishes the reference: "#123;x" after "&", "7b;x" after "&#x", "ace;x" after "&lbr"
        "<p>&[[s]]</p>|7",
        "<p>&#x[[s]]</p>|9",
        "<p>&lbr[[s]]</p>|10",
        "<title>&[[s]]</title>|11",
        "<textarea>&[[s]]</textarea>|14",
        "<p>&<t:block>[[s]]</t:block></p>|16",
        "<p>&<span t:remove=\"tag\" t:text=\"s\">x</span></p>|34",
        "<p><t:block t:if=\"${s != null}\">&</t:block>[[s]]</p>|46",
      })
  void valueAfterAmpersandFailsToBuild(String body, int column) {
    compile(body).assertError("page.html:2:" + column + ":", CHARREF);
  }

  @Test
  void valueInAFragmentAfterAmpersandFailsToBuild() {
    compile("<p>&<t:block t:replace=\"~{:: v(${s})}\"/></p><t:block t:fragment=\"v(String x)\">[[${x}]]</t:block>")
        .assertError("page.html:2:", "in fragment page :: v, inserted here", CHARREF);
  }

  @Test
  void messageWithAmpersandBeforeAnArgumentWritesABrokenBrace() {
    // The sanitizer decodes the reference the argument finishes and writes it as a text brace (#677).
    Compilation c = Compilation.builder()
        .template("page.html", "<div t:params=\"String s\">\n<p>[(#{m(${s})})]</p>\n</div>")
        .messages("m=x&{0}\n")
        .compile()
        .assertSuccess();
    RenderContext ctx = new RenderContext(
        Locale.US, (k, a) -> new java.text.MessageFormat("x&{0}", Locale.US).format(a), new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<p>x{x</p>\n</div>", c.render("page", Map.of("s", "#123;x"), ctx));
  }

  @Test
  void uncheckedMessageEndingInAmpersandIsEscaped() {
    // The build checked m=ok; a message source it never saw returns "&", which the value would finish.
    Compilation c = Compilation.builder()
        .template("page.html", "<div t:params=\"String s\">\n<p>[(#{m})][[s]]</p>\n</div>")
        .messages("m=ok\n")
        .compile()
        .assertSuccess();
    RenderContext ctx = new RenderContext(Locale.US, (k, a) -> "&", new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<p>&amp;#123;x</p>\n</div>", c.render("page", Map.of("s", "#123;x"), ctx));
  }

  @Test
  void ampersandThatEndsAReferenceStillCompiles() {
    assertEquals("<p>&amp;#123;</p>", render("<p>&amp;[[s]]</p>", "#123;"));
    assertEquals("<p>&nbsp;#123;</p>", render("<p>&nbsp;[[s]]</p>", "#123;"));
    assertEquals("<p>&nbsp; #123;</p>", render("<p>&nbsp; [[s]]</p>", "#123;"));
    assertEquals("<p>& #123;</p>", render("<p>& [[s]]</p>", "#123;"));
    // An escaped value never ends in a raw '&'.
    assertEquals("<p>&amp;&amp;</p>", render("<p>[[a]][[b]]</p>", "&"));
  }

  @Test
  void ampersandInAComputedAttributeIsEncoded() {
    assertEquals("<p title=\"&amp;#123;\">x</p>", render("<p t:title=\"|&${s}|\">x</p>", "#123;"));
    assertEquals("<p title=\"&amp;#123;\">x</p>", render("<p t:title=\"'&' + s\">x</p>", "#123;"));
    assertEquals("<p class=\"a&amp; #123;\">x</p>", render("<p class=\"a&amp;\" t:classappend=\"s\">x</p>", "#123;"));
    // A static attribute has no holes: [[...]] in it is text.
    assertEquals("<p title=\"&[[s]]\">x</p>", render("<p title=\"&[[s]]\">x</p>", "#123;"));
  }

  @Test
  void ampersandBeforeAUrlValueCannotSpellAScheme() {
    // "&#106;avascript:" would decode to "javascript:"; the '&' is encoded, so the URL stays relative.
    String s = "#106;avascript:alert(1)";
    String encoded = "<a href=\"&amp;#106;avascript:alert(1)\">x</a>";
    assertEquals(encoded, render("<a t:href=\"|&${s}|\">x</a>", s));
    assertEquals(encoded, render("<a t:href=\"'&' + s\">x</a>", s));
    assertEquals(encoded, render("<a t:attr=\"href=|&${s}|\">x</a>", s));
  }
}
