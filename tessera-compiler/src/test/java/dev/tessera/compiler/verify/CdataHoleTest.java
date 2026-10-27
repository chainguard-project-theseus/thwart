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
        .assertError("t.html:2:6: error: the HTML parser reports \"Bogus comment.\"", "[verifier:PARSE_ERROR]");
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
  }
}
