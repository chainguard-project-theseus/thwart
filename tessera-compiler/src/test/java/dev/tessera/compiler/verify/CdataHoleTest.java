package dev.tessera.compiler.verify;

import dev.tessera.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * An expression in a CDATA section (#411). {@code HtmlParser} rejected {@code [[...]]} and {@code
 * [(...)]} there with an early error; the verifier rejects the same templates, naming the CDATA
 * section and the fix, so that rule is gone. These are its former negative tests: each still fails
 * to compile, at the template's file, line and column.
 */
class CdataHoleTest {
  private static final String HEAD =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\" t:params=\"String x, SafeHtml h\">\n";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEAD + body + "\n</div>").compile();
  }

  @Test
  void outsideSvgAndMathTheSectionIsABogusComment() {
    // A browser reads <![CDATA[ in HTML content as a comment that ends at the first '>'.
    String comment = "a browser reads this value inside a comment that a '<!' or '<?' started and the next '>' ends,"
        + " not as text; write the '<' of the '<!' or '<?' before it as &lt; [verifier:TEXT_NOT_IN_TEXT]";
    compile("<div><![CDATA[ [[${x}]] ]]></div>")
        .assertError("t.html:2:20: error: " + comment)
        .assertError("t.html:2:6: error: a browser reads <![CDATA[ outside <svg> and <math> as the start of a comment",
            "write the text without <![CDATA[ and ]]>, with '<' as &lt; and '>' as &gt; [verifier:PARSE_ERROR]");
    compile("<![CDATA[[${x}]]>").assertError("t.html:2:13: error: " + comment);
    compile("<svg><foreignObject><![CDATA[ [[${x}]] ]]></foreignObject></svg>")
        .assertError("t.html:2:35: error: a browser reads this value inside a CDATA section", "[verifier:TEXT_NOT_IN_TEXT]");
    compile("<p><![CDATA[ [(${h})] ]]></p>")
        .assertError("t.html:2:18: error: a browser parses this SafeHtml value inside a comment that a '<!' or '<?'"
            + " started and the next '>' ends", "[verifier:RAW_NOT_FLOW]");
  }

  @Test
  void insideSvgAndMathTheValueIsNotText() {
    String cdata = "a browser reads this value inside a CDATA section, not as text; move the expression out of the"
        + " CDATA section [verifier:TEXT_NOT_IN_TEXT]";
    compile("<svg><![CDATA[ [[${x}]] ]]></svg>").assertError("t.html:2:20: error: " + cdata);
    compile("<math><![CDATA[ [[${x}]] ]]></math>").assertError("t.html:2:21: error: " + cdata);
    compile("<svg><text><![CDATA[ a [[${x}]] ]]></text></svg>").assertError("t.html:2:28: error: " + cdata);
    compile("<math><mi><![CDATA[ [[${x}]] ]]></mi></math>").assertError("t.html:2:25: error: " + cdata);
  }

  @Test
  void rawOutputInSvgOrMathIsAProfileError() {
    // [(...)] outside HTML content is the profile's own error, before the verifier runs.
    String profile = "[(...)] with a SafeHtml value or HTML message is only allowed in HTML content, not in <svg>"
        + " content, where a browser parses its markup differently: write the markup in the template, or move the"
        + " [(...)] into HTML content";
    compile("<svg><![CDATA[ [(${h})] ]]></svg>").assertError("t.html:2:18: error: " + profile);
    compile("<svg><![CDATA[ [(${x})] ]]></svg>").assertError("t.html:2:18: error: " + profile);
    compile("<math><![CDATA[ [(${h})] ]]></math>").assertError("t.html:2:19: error: " + profile.replace("<svg>", "<math>"));
  }

  @Test
  void withoutAnExpressionACdataSectionInSvgStillCompiles() {
    compile("<svg><text><![CDATA[ a < b ]]></text></svg><p>[[${x}]]</p>").assertSuccess();
    // SVG <style> and <script> and MathML outside the text integration points aren't integration
    // points: browsers agree on CDATA there (#441).
    compile("<svg><style><![CDATA[ a > b { fill: red } ]]></style></svg><p>[[${x}]]</p>").assertSuccess();
    compile("<svg><script><![CDATA[ if (a > b) f(); ]]></script></svg><p>[[${x}]]</p>").assertSuccess();
    compile("<svg><g><![CDATA[ a < b ]]></g></svg><math><mrow><![CDATA[ a < b ]]></mrow></math><p>[[${x}]]</p>")
        .assertSuccess();
  }

  private static final String INTEGRATION_POINT = ", an integration point: Firefox reads a CDATA section there, as the"
      + " standard does, but Chrome reads a comment that ends at the first '>', so the markup after it would differ;"
      + " write the text without <![CDATA[ and ]]>, with '<' as &lt; and '>' as &gt; [verifier:CDATA_IN_INTEGRATION_POINT]";

  /**
   * Exclusion (d) (#441): no CDATA section in an HTML or MathML text integration point. There the
   * engine reads a CDATA section, but parse5 and Chromium read a comment that ends at the first
   * {@code >}, so in their reading the {@code <script>} is live and the later hole is in it.
   * {@code HtmlParser} rejected the SVG {@code <title>} case with a '>' only as an early error
   * ({@code foreignTitleCdataEnd}, deleted); these are its former negative tests too.
   */
  @Test
  void aCdataSectionInAnIntegrationPointIsRejected() {
    compile("<svg><title><![CDATA[ > <script> ]]></title></svg><p>[[${x}]]</p>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    compile("<svg><title><![CDATA[ > <img src=x title=\" ]]></title></svg><p>[[${x}]]</p>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    compile("<svg><title><![CDATA[ > <script>[[${x}]]</script> ]]></title></svg>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    compile("<svg><title><![CDATA[ ]] > <img src=x onerror=[[${x}]]> ]]></title></svg>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    compile("<svg><title><![CDATA[ a ]]><![CDATA[ > <script>[[${x}]]</script> ]]></title></svg>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    // Coarse: without a '>' too, and in every integration point. (Outside raw text, HtmlParser
    // rejects a '>' in a CDATA section itself.)
    compile("<svg><title><![CDATA[ a < b ]]> [[${x}]]</title></svg>")
        .assertError("t.html:2:13: error: a CDATA section in SVG <title>" + INTEGRATION_POINT);
    compile("<svg><desc><![CDATA[ a < b ]]></desc></svg>")
        .assertError("t.html:2:12: error: a CDATA section in SVG <desc>" + INTEGRATION_POINT);
    compile("<svg><foreignObject><![CDATA[ a < b ]]></foreignObject></svg>")
        .assertError("t.html:2:21: error: a CDATA section in SVG <foreignObject>" + INTEGRATION_POINT);
    compile("<math><mi><![CDATA[ a < b ]]></mi></math>")
        .assertError("t.html:2:11: error: a CDATA section in MathML <mi>" + INTEGRATION_POINT);
    compile("<math><annotation-xml encoding=\"text/html\"><![CDATA[ a < b ]]></annotation-xml></math>")
        .assertError("t.html:2:44: error: a CDATA section in MathML <annotation-xml>" + INTEGRATION_POINT);
  }

  /** A MathML {@code <title>} isn't an integration point: the hole is in the CDATA section in every browser. */
  @Test
  void aCdataSectionInAMathTitleHidesTheHole() {
    compile("<math><title><![CDATA[ > <script>[[${x}]]</script> ]]></title></math>")
        .assertError("t.html:2:38: error: a browser reads this value inside a CDATA section", "[verifier:TEXT_NOT_IN_TEXT]");
  }
}
