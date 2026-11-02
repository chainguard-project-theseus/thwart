package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.io.IOException;
import java.io.StringReader;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code t:utext="#{key(args)}"} and {@code [(#{...})]}: bundle messages rendered as HTML, sanitized
 * by the fixed message policy at runtime (#677).
 */
class HtmlMessageTest {
  private static final String BUNDLE =
      "intro=See <a href=\"/phase-out\" class=\"alert-link\">the phase-out</a>.<br>Next paragraph.\n"
          + "greet=Hello <b>{0}</b>, you have {1,number,integer} items\n"
          + "plain=Don''t <i>panic</i>\n";

  private static final String PAGE =
      "<div t:params=\"String name, int n\">"
          + "<p t:utext=\"#{intro}\">x</p>"
          + "<p t:utext=\"#{greet(name, n)}\">x</p>"
          + "<span>[(#{greet(name, n)})]</span>"
          + "</div>";

  private static final String INTRO =
      "See <a href=\"/phase-out\" rel=\"noopener noreferrer\">the phase-out</a>.<br>Next paragraph.";

  /** Like Spring: MessageFormat only when there are arguments. */
  private static RenderContext ctx(String properties) {
    Properties p = new Properties();
    try {
      p.load(new StringReader(properties));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return new RenderContext(
        Locale.ROOT,
        (k, a) -> a.length == 0 ? p.getProperty(k) : new MessageFormat(p.getProperty(k), Locale.ROOT).format(a),
        new DefaultLinks(""),
        Forms.NONE);
  }

  private static Compilation compile(String bundle, String template) {
    return Compilation.builder().template("page.html", template).messages(bundle).compile();
  }

  @Test
  void rendersTheMessageSanitized() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    String html = c.render("page", Map.of("name", "Ann", "n", 1234), ctx(BUNDLE));
    assertEquals(
        "<div><p>" + INTRO + "</p>"
            + "<p>Hello <b>Ann</b>, you have 1,234 items</p>"
            + "<span>Hello <b>Ann</b>, you have 1,234 items</span></div>",
        html);
  }

  @Test
  void argumentsAreEscaped() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    String html = c.render("page", Map.of("name", "<script>alert(1)</script>\"'&", "n", 1), ctx(BUNDLE));
    assertEquals(
        "<div><p>" + INTRO + "</p>"
            + "<p>Hello <b>&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;&amp;</b>, you have 1 items</p>"
            + "<span>Hello <b>&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;&amp;</b>, you have 1 items</span></div>",
        html);
  }

  @Test
  void escapedFormStillEscapesTheMessage() {
    Compilation c = compile(BUNDLE, "<p t:params=\"String name\" t:text=\"#{intro}\">x</p>").assertSuccess();
    assertEquals(
        "<p>See &lt;a href=&quot;/phase-out&quot; class=&quot;alert-link&quot;&gt;the phase-out&lt;/a&gt;."
            + "&lt;br&gt;Next paragraph.</p>",
        c.render("page", Map.of("name", "x"), ctx(BUNDLE)));
  }

  @Test
  void quotedApostropheIsFormattedWhenThereAreArguments() {
    Compilation c =
        compile(BUNDLE, "<p t:params=\"String s\" t:utext=\"#{plain(s)}\">x</p>").assertSuccess();
    assertEquals("<p>Don&#39;t <i>panic</i></p>", c.render("page", Map.of("s", "x"), ctx(BUNDLE)));
  }

  @Test
  void worksWithoutTheBundleOption() {
    // Nothing about the markup is checked at build time; an unchecked bundle's messages keep links.
    Compilation c =
        Compilation.builder().template("page.html", "<div>\n<p t:utext=\"#{intro}\">x</p></div>").compile().assertSuccess();
    assertEquals("<div>\n<p>" + INTRO + "</p></div>", c.render("page", Map.of(), ctx(BUNDLE)));
  }

  @Test
  void unknownKeyFails() {
    compile(BUNDLE, "<div>\n<p t:utext=\"#{intr}\">x</p></div>")
        .assertError("page.html:2:", "unknown message key 'intr'", "messages.properties");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Hi<script>alert(1)</script>",
        "Hi<img src=x onerror=alert(1)>",
        "<a href=\"javascript:alert(1)\">Hi</a>",
        "<a href=\"java&#115;cript:alert(1)\">Hi</a>",
        "<b onclick=\"alert(1)\" style=\"color:red\">Hi</b>",
        "<style>p{}</style>Hi",
        "<!-- x -->Hi",
        "</div></p>Hi<div>",
        "<p>Hi",
        "<svg onload=alert(1)>Hi</svg>",
        "<form action=/x>Hi<input name=a></form>",
        "<textarea>Hi</textarea>",
      })
  void hostileBundleValuesCompileAndRenderOnlyThePolicysMarkup(String value) {
    Properties p = new Properties();
    p.setProperty("bad", value);
    java.io.StringWriter w = new java.io.StringWriter();
    try {
      p.store(w, null);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    Compilation c = compile(w.toString(), "<div>\n<p t:utext=\"#{bad}\">x</p></div>").assertSuccess();
    String html = c.render("page", Map.of(), ctx(w.toString()));
    assertFalse(
        html.matches("(?s).*(script|onclick|onerror|onload|style|javascript|<img|<svg|<form|<input|<textarea|<p><p|<div><div).*"),
        html);
    assertTrue(html.startsWith("<div>\n<p>") && html.endsWith("</p></div>"), html);
  }

  @ParameterizedTest
  @ValueSource(strings = {"k=&#123;\n", "k=&#x7b;{0}\n", "k=&lbrace;\n"})
  void braceReferenceAtAMessageEdgeIsBroken(String bundle) {
    // #336: a reference for '{' is decoded and written as a text brace, broken from its neighbours.
    String arg = bundle.contains("{0}") ? "(s)" : "";
    Compilation c =
        compile(bundle, "<div t:params=\"String s\">\n<p>[(#{k" + arg + "})][[s]]</p></div>").assertSuccess();
    String html = c.render("page", Map.of("s", "{"), ctx(bundle));
    assertFalse(html.contains("{{"), html);
  }

  @Test
  void runtimeMessageTheBuildDidNotSeeIsSanitized() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    // At runtime the message source returns other values for the keys (a bundle in another
    // directory or jar, a database, a file changed after the build).
    String evil =
        "intro=<script>alert(1)</script><b>bold</b>\n"
            + "greet=<img src=x onerror=alert({0})>{1}\n";
    String html = c.render("page", Map.of("name", "Ann", "n", 1), ctx(evil));
    assertEquals("<div><p><b>bold</b></p><p>1</p><span>1</span></div>", html);
  }
}
