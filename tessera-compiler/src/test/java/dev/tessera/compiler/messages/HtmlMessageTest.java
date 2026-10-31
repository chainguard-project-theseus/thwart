package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

/** {@code t:utext="#{key(args)}"} and {@code [(#{...})]}: bundle messages rendered as HTML (#236). */
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
  void rendersCheckedMessageAsHtml() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    String html = c.render("page", Map.of("name", "Ann", "n", 1234), ctx(BUNDLE));
    assertEquals(
        "<div><p>See <a href=\"/phase-out\" class=\"alert-link\">the phase-out</a>.<br>Next paragraph.</p>"
            + "<p>Hello <b>Ann</b>, you have 1,234 items</p>"
            + "<span>Hello <b>Ann</b>, you have 1,234 items</span></div>",
        html);
  }

  @Test
  void argumentsAreEscaped() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    String html = c.render("page", Map.of("name", "<script>alert(1)</script>\"'&", "n", 1), ctx(BUNDLE));
    assertEquals(
        "<div><p>See <a href=\"/phase-out\" class=\"alert-link\">the phase-out</a>.<br>Next paragraph.</p>"
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
    assertEquals("<p>Don't <i>panic</i></p>", c.render("page", Map.of("s", "x"), ctx(BUNDLE)));
  }

  @Test
  void needsTheBundleOption() {
    Compilation c =
        Compilation.builder().template("page.html", "<div>\n<p t:utext=\"#{intro}\">x</p></div>").compile();
    c.assertError("page.html:2:", "t:utext with a message #{...} needs -Atessera.messages");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void unknownKeyFails() {
    compile(BUNDLE, "<div>\n<p t:utext=\"#{intr}\">x</p></div>")
        .assertError("page.html:2:", "unknown message key 'intr'", "messages.properties");
  }

  @Test
  void scriptInBundleFailsNamingFileAndKey() {
    Compilation c = compile("bad=Hi<script>alert(1)</script>\n", "<div>\n<p t:utext=\"#{bad}\">x</p></div>");
    c.assertError(
        "page.html:2:", "message 'bad' in ", "messages.properties cannot be rendered as HTML by t:utext",
        "<script> is not allowed");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  /**
   * #336: with k=&#123; the page would render {&#123;VALUE }}, which Vue runs: the runtime breaks
   * only a literal '{' at a message's edges, so a brace reference in a checked message fails.
   */
  @ParameterizedTest
  @ValueSource(strings = {"k=&#123;\n", "k=&#x7b;{0}\n", "k=&lbrace;\n"})
  void braceReferenceAtAMessageEdgeFails(String bundle) {
    String arg = bundle.contains("{0}") ? "(s)" : "";
    Compilation c = compile(bundle, "<div t:params=\"String s\">\n<p>{[(#{k" + arg + "})]}[[s]] }}</p></div>");
    c.assertError("page.html:2:", "message 'k'", "a character reference for '{' or '}' is not supported");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"));
  }

  @Test
  void eventHandlerInBundleFails() {
    compile("bad=<b onclick=\"alert(1)\">x</b>\n", "<div>\n<span>[(#{bad})]</span></div>")
        .assertError("page.html:2:", "message 'bad'", "by [(...)]", "attribute onclick is not allowed on <b>");
  }

  @Test
  void badLocaleFileFailsNamingIt() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div>\n<p t:utext=\"#{intro}\">x</p></div>")
            .messages("intro=<b>Hi</b>\n")
            .localeMessages("fr", "intro=<b>Salut</b><img src=x onerror=alert(1)>\n")
            .localeMessages("pt_BR", "intro=<b>Oi</b>\n")
            .compile();
    c.assertError("page.html:2:", "message 'intro' in ", "messages_fr.properties", "<img> is not allowed");
    assertEquals(1, c.errors().size(), c.diagnostics().toString());
  }

  @Test
  void placeholderInAttributeFails() {
    compile("bad=<a href=\"{0}\">x</a>\n", "<div t:params=\"String u\">\n<p t:utext=\"#{bad(u)}\">x</p></div>")
        .assertError("page.html:2:", "a placeholder may not be inside a tag (attribute href of <a>)");
  }

  @Test
  void messageIsOnlyCheckedWhereItIsRenderedAsHtml() {
    compile("bad=<script>x</script>\n", "<p t:text=\"#{bad}\">x</p>").assertSuccess();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<a href=\"javascript:alert(1)\">x</a>",
        "<a href=\"java&#115;cript:alert(1)\">x</a>",
        "<style>p{}</style>",
        "<p style=\"color:red\">x</p>",
        "<!-- x -->",
        "</div>",
        "<div>x",
        "<b>{0,choice,0#a|1#<script>}</b>",
      })
  void unsafeOrMalformedBundleValuesFail(String value) {
    Properties p = new Properties();
    p.setProperty("bad", value);
    java.io.StringWriter w = new java.io.StringWriter();
    try {
      p.store(w, null);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    Compilation c = compile(w.toString(), "<div>\n<p t:utext=\"#{bad}\">x</p></div>");
    c.assertError("page.html:2:", "message 'bad'", "cannot be rendered as HTML");
  }

  @Test
  void runtimeMessageTheBuildDidNotCheckIsEscaped() {
    Compilation c = compile(BUNDLE, PAGE).assertSuccess();
    // At runtime the message source returns other values for the checked keys (a bundle in
    // another directory or jar, a database, a file changed after the build).
    String evil =
        "intro=<script>alert(1)</script>\n"
            + "greet=<img src=x onerror=alert({0})>{1}\n";
    String html = c.render("page", Map.of("name", "Ann", "n", 1), ctx(evil));
    assertEquals(
        "<div><p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
            + "<p>&lt;img src=x onerror=alert(Ann)&gt;1</p>"
            + "<span>&lt;img src=x onerror=alert(Ann)&gt;1</span></div>",
        html);
  }

  @Test
  void runtimeMessageFromALocaleFileTheBuildCheckedIsHtml() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<p t:utext=\"#{intro}\">x</p>")
            .messages("intro=<b>Hi</b>\n")
            .localeMessages("fr", "intro=<b>Salut</b>\n")
            .compile()
            .assertSuccess();
    assertEquals("<p><b>Salut</b></p>", c.render("page", Map.of(), ctx("intro=<b>Salut</b>\n")));
    assertEquals("<p>&lt;i&gt;Salut&lt;/i&gt;</p>", c.render("page", Map.of(), ctx("intro=<i>Salut</i>\n")));
  }
}
