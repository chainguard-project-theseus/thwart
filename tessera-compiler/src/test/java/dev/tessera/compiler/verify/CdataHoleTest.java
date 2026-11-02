package dev.tessera.compiler.verify;

import dev.tessera.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * CDATA sections (#411, #441, #678). A browser reads {@code <![CDATA[} as a CDATA section only in SVG and
 * MathML, as a bogus comment elsewhere, and some browsers as a comment in an integration point. Tessera
 * doesn't model that: every CDATA section is a profile error at its start, and the verifier rejects it
 * too ({@link Problem#CDATA_SECTION} in foreign content, a parse error in HTML content).
 */
class CdataHoleTest {
  private static final String HEAD =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\" t:params=\"String x, SafeHtml h\">\n";

  private static Compilation.Builder builder(String body) {
    return Compilation.builder().template("t.html", HEAD + body + "\n</div>");
  }

  @Test
  void everyCdataSectionIsAProfileError() {
    String error = "error: a CDATA section is not supported: a browser reads <![CDATA[ by different rules inside and"
        + " outside <svg> and <math>; write the text without <![CDATA[ and ]]>";
    builder("<div><![CDATA[ [[${x}]] ]]></div>").compile().assertError("t.html:2:6: " + error);
    builder("<svg><text><![CDATA[ x < y ]]></text></svg>").compile().assertError("t.html:2:12: " + error);
    builder("<svg><foreignObject><![CDATA[ x ]]></foreignObject></svg>").compile().assertError("t.html:2:21: " + error);
    builder("<math><mi><![CDATA[ [(${h})] ]]></mi></math>").compile().assertError("t.html:2:11: " + error);
  }

  @Test
  void theVerifierRejectsThemToo() {
    for (String body : new String[] {"<svg><text><![CDATA[ x ]]></text></svg>",
        "<svg><foreignObject><![CDATA[ x ]]></foreignObject></svg>", "<math><mi><![CDATA[ x ]]></mi></math>"}) {
      builder(body).verifierOnly().compile().assertError("t.html:2:", "a CDATA section in", "[verifier:CDATA_SECTION]");
    }
    builder("<p><![CDATA[ x ]]></p>").verifierOnly().compile().assertError("t.html:2:4:", "[verifier:PARSE_ERROR]");
  }
}
