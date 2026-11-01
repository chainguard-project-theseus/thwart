package dev.tessera.compiler.security;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.SafeHtml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Elements whose content a browser reads as raw text or RCDATA while Tessera parses it as markup:
 * {@code <xmp> <iframe> <noembed> <noframes> <noscript>} (scripting on), and what follows a
 * minimized {@code <textarea/>} or {@code <title/>}. The browser ends that text at the first
 * {@code </name}; wherever Tessera reads that end tag differently, or HTML known only at runtime
 * could contain it, the template must not build.
 */
class BrowserRawTextTest {
  private static final String XSS = "</noscript></iframe></xmp></textarea><script>alert(1)</script>";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"String s, SafeHtml h\">\n" + body + "\n</div>")
        .compile();
  }

  private static String render(String body) {
    return compile(body).assertSuccess().render("page", model("s", XSS, "h", SafeHtml.unchecked("<b>h</b>")));
  }

  @Test
  void endTagHiddenFromTesseraIsAnError() {
    compile("<xmp><i title=\"</xmp><script>/*\"></i></xmp>*/\n[[s]]\n<!-- </script> -->")
        .assertError("page.html:2:16:", "content of <xmp> contains '</xmp' before its end tag");
    compile("<noscript><!-- </noscript><script> --></noscript>\n[[s]]\n<!-- </script> -->")
        .assertError("page.html:2:16:", "content of <noscript> contains '</noscript' before its end tag");
    compile("<iframe><script>var a = \"</IFRAME>\";</script></iframe>")
        .assertError("page.html:2:26:", "content of <iframe> contains '</iframe' before its end tag");
    compile("<noembed><p title=\"</noembed \"></p></noembed>")
        .assertError("page.html:2:20:", "content of <noembed> contains '</noembed' before its end tag");
    compile("<noframes><textarea></noframes></textarea></noframes>")
        .assertError("page.html:2:21:", "content of <noframes> contains '</noframes' before its end tag");
    // A browser ends the outer <noscript> at the inner one's end tag.
    compile("<noscript><noscript></noscript></noscript>")
        .assertError("page.html:2:21:", "content of <noscript> contains '</noscript' before its end tag");
  }

  @Test
  void endTagChecksAlsoApplyInsideRemoveAll() {
    // The guide says so: Tessera still parses a t:remove="all" subtree, though it is not rendered.
    compile("<div t:remove=\"all\"><noscript><!-- </noscript> --></noscript></div>")
        .assertError("page.html:2:36:", "content of <noscript> contains '</noscript' before its end tag");
    compile("<div t:remove=\"all\"><script>var a = \"</ſcript>\";</script></div>")
        .assertError("page.html:2:38:", "'</ſcript' inside <script> looks like its end tag but a browser doesn't end"
            + " the element there");
  }

  @Test
  void plaintextIsAnError() {
    compile("<p><plaintext>[[s]]</plaintext></p>")
        .assertError("page.html:2:4:", "<plaintext> is not supported: a browser treats the rest of the page as plain"
            + " text; use <pre>");
  }

  @Test
  void selfClosingRawTextElementWrittenAsIsIsAnError() {
    compile("<textarea/><!-- </textarea><script> -->\n[[s]]\n<!-- </script> -->")
        .assertError("page.html:2:1:", "<textarea/> is not self-closing in HTML: the browser treats everything after"
            + " it as textarea; write <textarea ...></textarea>");
    compile("<p><title/></p>").assertError("page.html:2:4:", "<title/> is not self-closing");
    compile("<IFrame src=\"/x\"/>").assertError("page.html:2:1:", "<iframe/> is not self-closing");
    compile("<noscript/>").assertError("page.html:2:1:", "<noscript/> is not self-closing");
    compile("<xmp class=\"c\" />").assertError("page.html:2:1:", "<xmp/> is not self-closing");
    compile("<svg><foreignObject><textarea/></foreignObject></svg>")
        .assertError("page.html:2:21:", "<textarea/> is not self-closing");
    // HTML integration points and breakout tags make <title> RCDATA again inside <svg> and <math>.
    compile("<svg><foreignObject><title/><!-- </title><script> -->\n[[s]]\n<!-- </script> --></foreignObject></svg>")
        .assertError("page.html:2:21:", "<title/> is not supported inside <svg> or <math>: Tessera cannot tell an SVG title");
    compile("<math><mtext><title/></mtext></math>").assertError("page.html:2:14:", "<title/> is not supported inside <svg> or <math>: Tessera cannot tell an SVG title");
    compile("<svg><title/></svg>")
        .assertError("page.html:2:6:", "<title/> is not supported inside <svg> or <math>: Tessera cannot tell an SVG"
            + " title from an HTML one (in <foreignObject> or after <b>), after which a browser reads the rest of the"
            + " page as title text; write <title></title>");
  }

  @Test
  void selfClosingElementsTesseraWritesWithAnEndTagStillBuild() {
    assertEquals(
        "<div>\n<textarea v-pre>\n&lt;/noscript&gt;&lt;/iframe&gt;&lt;/xmp&gt;&lt;/textarea&gt;&lt;script&gt;alert(1)"
            + "&lt;/script&gt;</textarea><title v-pre>x</title>\n</div>",
        render("<textarea t:text=\"s\"/><title t:text=\"'x'\"/>"));
    assertEquals("<div>\n<svg><title></title></svg>\n</div>", render("<svg><title></title></svg>"));
    assertEquals("<div>\n\n</div>", render("<textarea t:remove=\"all\"/>"));
  }

  @Test
  void runtimeHtmlInsideRawTextIsAnError() {
    String msg = "inside <noscript> is not supported: a browser reads the content of <noscript> as text up to the"
        + " first '</noscript', and inserted markup could contain one; move it outside the element";
    compile("<noscript t:utext=\"h\"></noscript>").assertError("page.html:2:11:", "t:utext " + msg);
    compile("<noscript><p><b t:utext=\"h\"></b></p></noscript>").assertError("page.html:2:17:", "t:utext " + msg);
    compile("<noscript>a [(h)]</noscript>").assertError("page.html:2:13:", "[(...)] " + msg);
    compile("<b t:fragment=\"f\">x</b><noscript t:insert=\"~{::f}\"></noscript>")
        .assertError("page.html:2:34:", "t:insert " + msg);
    compile("<b t:fragment=\"f\">x</b><iframe><i t:replace=\"~{::f}\"></i></iframe>")
        .assertError("page.html:2:35:", "t:replace inside <iframe> is not supported");
    compile("<xmp><noscript t:replace=\"~{::f}\"></noscript></xmp><b t:fragment=\"f\">x</b>")
        .assertError("page.html:2:16:", "t:replace inside <xmp> is not supported");
    // Content that t:replace, t:text or a parameterized t:fragment replaces is never written there.
    compile("<p t:fragment=\"f\">ok</p><noscript t:replace=\"~{::f}\">[(h)]<b t:utext=\"h\"></b></noscript>")
        .assertSuccess();
    // The fragment itself is written only by its own method, and the verifier's one error is that: no
    // template inserts it, and declared in raw text, where no fragment fits, it can't be checked on
    // its own (#461).
    Compilation g = compile("<noscript><p t:fragment=\"g(SafeHtml x)\" t:utext=\"x\"></p></noscript>");
    g.assertError("page.html:2:", "error: no template inserts fragment page :: g", "[verifier:UNCALLED_FRAGMENT_FITS_NOWHERE]");
    assertEquals(1, g.errors().size(), g.errors().toString());
    // A quoted ')]' does not end [( , as for t:text: this is literal text.
    compile("<noscript>[( ')]' </noscript>").assertSuccess();
    // <textarea> and <title> (RCDATA) end at the first </textarea or </title the same way.
    compile("<textarea t:utext=\"h\"></textarea>").assertError("page.html:2:11:", "t:utext inside <textarea> is not supported");
    compile("<title>[(h)]</title>").assertError("page.html:2:8:", "[(...)] inside <title> is not supported");
    compile("<p t:fragment=\"f\">x</p><textarea t:insert=\"~{::f}\"></textarea>")
        .assertError("page.html:2:34:", "t:insert inside <textarea> is not supported");
  }

  @Test
  void ordinaryContentStillBuilds() {
    // The Google Tag Manager snippet, static content, and t:replace of the element itself.
    compile("<noscript><iframe src=\"https://www.googletagmanager.com/ns.html?id=GTM-X\" height=\"0\" width=\"0\""
            + " style=\"display:none;visibility:hidden\"></iframe></noscript>\n"
            + "<noscript><p>x</p></noscript><noembed><!-- c --></noembed><xmp>a [( b</xmp>\n"
            + "<b t:fragment=\"f\">x</b><noscript t:replace=\"~{::f}\"></noscript><div t:utext=\"h\"></div>"
            + "<xmp t:if=\"${s != null}\">x</xmp>")
        .assertSuccess();
  }

  /**
   * A browser reads the content of these elements as raw text (#571), where an empty comment is
   * text and can't break a value's {{, so no value, condition or loop may write there: not in
   * text, not in an attribute of an element inside, not from t:text on the element.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<xmp>[[s]]</xmp>|xmp",
        "<noembed>a [[s]] b</noembed>|noembed",
        "<noframes><p t:text=\"s\">x</p></noframes>|noframes",
        "<iframe t:text=\"s\"></iframe>|iframe",
        "<noscript><p t:title=\"s\">x</p></noscript>|noscript",
        "<noscript><script>var a = [[s]];</script></noscript>|noscript",
        "<noscript><t:block t:if=\"${s != null}\">{{ x }}</t:block></noscript>|noscript",
        "<NOSCRIPT><p t:unless=\"${s == null}\">x</p></NOSCRIPT>|noscript",
      })
  void computedContentInsideRawTextIsAnError(String bodyAndElement) {
    int bar = bodyAndElement.lastIndexOf('|');
    compile(bodyAndElement.substring(0, bar))
        .assertError("page.html:2:1:", "a value, condition or loop inside <" + bodyAndElement.substring(bar + 1)
            + "> is not supported", "write the content as static text");
  }

  /**
   * The verifier, not only the generator, rejects a value in raw text (#586): a POST form inside
   * {@code <noscript>} gets the CSRF hidden input, a value in raw text with scripting on.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<noscript><form method=\"post\" action=\"/x\"><button>Go</button></form></noscript>",
        "<noscript><p t:title=\"s\">x</p></noscript>",
        "<xmp>[[s]]</xmp>",
        "<iframe t:text=\"s\"></iframe>",
      })
  void verifierRejectsAValueInRawText(String body) {
    Compilation.builder()
        .template("page.html", "<div t:params=\"String s, SafeHtml h\">\n" + body + "\n</div>")
        .verifierOnly()
        .compile()
        .assertError("page.html:2:", "[verifier:VALUE_IN_RAW_TEXT]");
  }
}
