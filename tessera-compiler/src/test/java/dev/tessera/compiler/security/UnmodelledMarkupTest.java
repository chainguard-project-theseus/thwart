package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #678: markup Tessera doesn't model is a build error with a fix-it at its position, not a rule:
 * values in SVG and MathML, CDATA sections, {@code xmp plaintext noembed noframes frameset}, values in
 * {@code noscript} and {@code iframe}, computed URL lists and the attributes the tree builder reads.
 */
class UnmodelledMarkupTest {

  private static Compilation compile(String body) {
    return builder(body).compile();
  }

  private static Compilation.Builder builder(String body) {
    return Compilation.builder()
        .template("t.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s, boolean b, int n\">" + body + "</div>");
  }

  /**
   * Foreign content is by tag (#759): the parser does not model what a browser reads as HTML or as
   * markup inside {@code <svg>} and {@code <math>} (integration points, breakout tags, raw text that
   * holds markup there). Everything under them is foreign, so a value anywhere there is an error.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><title><script>[[${s}]]</script></title></svg>",
        "<svg><p><title>[[${s}]]</title></p></svg>",
        "<svg><style><p><script>/*</style>*/[[${s}]]//<script></script></svg>",
        "<math><textarea><img src=x onerror=[[${s}]]></textarea></math>",
        "<svg><script>var a = &quot;[[${s}]]&quot;;</script></svg>",
        "<svg><script><![CDATA[ f([[${s}]]); ]]></script></svg>",
        "<svg><font color=\"red\"><b t:text=\"s\">x</b></font></svg>",
        "<math><annotation-xml encoding=\"text/html\"><p t:text=\"s\">x</p></annotation-xml></math>",
        "<math><annotation-xml encoding=\"text/html\"><a t:title=\"s\">x</a></annotation-xml></math>",
        "<svg><foreignObject><div t:class=\"s\">x</div></foreignObject></svg>",
      })
  void foreignContentIsByTag(String body) {
    compile(body).assertError("t.html:1:", "SVG and MathML by rules Tessera doesn't check");
  }

  /**
   * The verifier rejects the same markup on its own where a browser reads it as markup or foreign
   * content: it parses the composed output as a browser does (and so reads an HTML integration point
   * as HTML, which only the profile rejects).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><title><script>[[${s}]]</script></title></svg>",
        "<svg><p><title>[[${s}]]</title></p></svg>",
        "<svg><style><p><script>/*</style>*/[[${s}]]//<script></script></svg>",
        "<math><textarea><img src=x onerror=[[${s}]]></textarea></math>",
        "<svg><script>var a = &quot;[[${s}]]&quot;;</script></svg>",
        "<svg><script><![CDATA[ f([[${s}]]); ]]></script></svg>",
        "<svg><font color=\"red\"><b t:text=\"s\">x</b></font></svg>",
        "<svg><p>x</p></svg><p t:text=\"s\">y</p>",
      })
  void theVerifierRejectsForeignContentOnItsOwn(String body) {
    builder(body).verifierOnly().compile().assertError("t.html:1:", "[verifier:");
  }

  /**
   * Error-free markup where the parser's tree is balanced and the hole is outside {@code <svg>} for
   * Tessera, but a browser reads the raw text of an SVG {@code <title>} or {@code <style>} as markup
   * (an integration point there), so the hole is inside a {@code <script>}. The parser no longer
   * models that reading: the verifier rejects it as script on its own.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><title><script></title></svg><p>[[${s}]]</p><svg><title></script></title></svg>",
        "<svg><style><foreignObject><script></style></svg><p>[[${s}]]</p><svg><style></script></foreignObject></style></svg>",
      })
  void aScriptOpenedInForeignRawTextIsTheVerifiersError(String body) {
    // A full build fails too (for the first, on the parser's own end-tag check before the verifier runs).
    compile(body).assertError("t.html:1:");
    builder(body).verifierOnly().compile().assertError("t.html:1:", "[verifier:TEXT_IN_SCRIPT]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<svg><text t:text=\"s\">x</text></svg>",
        "<svg><text>[[${s}]]</text></svg>",
        "<svg><rect t:width=\"n\"/></svg>",
        "<svg><path t:stroke-dasharray=\"|${n}, 100|\"/></svg>",
        "<svg><g t:if=\"b\"><rect/></g></svg>",
        "<svg><g t:each=\"i : ${#numbers.sequence(1, 2)}\"><rect/></g></svg>",
        "<svg><foreignObject><p t:text=\"s\">x</p></foreignObject></svg>",
        "<svg t:class=\"s\"></svg>",
        "<svg t:attr=\"fill='red'\"></svg>",
        "<svg t:text=\"s\"></svg>",
        "<math><mi t:text=\"s\">x</mi></math>",
        "<math><annotation-xml t:encoding=\"'text/html'\"><mi>x</mi></annotation-xml></math>",
      })
  void valuesInsideSvgAndMathAreErrors(String body) {
    compile(body).assertError("t.html:1:", "SVG and MathML by rules Tessera doesn't check", "as static markup");
  }

  @Test
  void theErrorPointsAtTheValue() {
    compile("<svg>\n<g>\n  <text t:text=\"s\">x</text></g></svg>").assertError("t.html:3:", "inside <svg> is not supported");
  }

  @Test
  void theFixItNamesTheForeignRoot() {
    compile("<math><mi t:text=\"s\">x</mi></math>")
        .assertError("inside <math> is not supported", "nothing inside <math> is computed", "one static <math> per case");
    compile("<svg><text t:text=\"s\">x</text></svg>").assertError("one static <svg> per case");
  }

  @Test
  void staticSvgAndControlFlowAroundItStillCompile() {
    String body = "<svg viewBox=\"0 0 10 10\"><title>t</title><path d=\"M0 0\" fill=\"currentColor\"/></svg>"
        + "<svg t:if=\"b\"><rect/></svg><math><mi>x</mi></math>";
    assertEquals("<div><svg viewBox=\"0 0 10 10\"><title>t</title><path d=\"M0 0\" fill=\"currentColor\"/></svg>"
            + "<svg><rect/></svg><math><mi>x</mi></math></div>",
        compile(body).assertSuccess().render("t", Map.of("s", "x", "b", true, "n", 1)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"xmp", "plaintext", "noembed", "noframes", "frameset"})
  void unmodelledElementsAreErrors(String name) {
    compile("<" + name + ">x</" + name + ">").assertError("t.html:1:", "<" + name + "> is not supported", "use ");
  }

  @ParameterizedTest
  @ValueSource(strings = {"<noscript>[[${s}]]</noscript>", "<noscript><p t:if=\"b\">x</p></noscript>",
      "<iframe>[[${s}]]</iframe>", "<iframe><b t:text=\"s\">x</b></iframe>"})
  void valuesInNoscriptAndIframeAreErrors(String body) {
    compile(body).assertError("t.html:1:", "is not supported", "static text");
  }

  @ParameterizedTest
  @ValueSource(strings = {"<img t:srcset=\"'/a.png 1x'\">", "<link rel=\"preload\" as=\"image\" t:imagesrcset=\"'/a.png 1x'\">",
      "<a href=\"/x\" t:ping=\"'/p'\">x</a>", "<img t:attr=\"srcset='/a.png 1x'\">"})
  void urlListsAreForbiddenEvenAsLiterals(String body) {
    compile(body).assertError("t.html:1:", "is not allowed", "list of URLs", "single src or href");
  }

  @ParameterizedTest
  @ValueSource(strings = {"<input t:type=\"b ? 'text' : 'hidden'\">", "<script t:type=\"'module'\"></script>",
      "<p t:encoding=\"'x'\">x</p>", "<p t:is=\"'my-el'\">x</p>", "<p t:xmlns=\"'x'\">x</p>",
      "<p t:xmlns:foo=\"'x'\">x</p>", "<font t:color=\"'red'\">x</font>", "<font t:face=\"'serif'\">x</font>",
      "<font t:size=\"n\">x</font>", "<meta t:charset=\"'utf-8'\">", "<meta t:http-equiv=\"'refresh'\" content=\"0\">"})
  void treeBuilderInputsAreForbiddenEvenAsLiterals(String body) {
    compile(body).assertError("t.html:1:", "computed");
  }
}
