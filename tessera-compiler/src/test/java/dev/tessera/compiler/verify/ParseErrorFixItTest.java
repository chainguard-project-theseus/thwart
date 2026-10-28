package dev.tessera.compiler.verify;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fix-its for the parse errors behind {@code HtmlParser}'s former early errors (#442). The verifier
 * rejects these templates as {@link Problem#PARSE_ERROR}; its message now names the markup and the
 * fix, at the markup's {@code <}. {@code HtmlParser}'s rules for self-closing {@code <script/>},
 * {@code <style/>} and {@code <select/>}, for a {@code >} in a {@code <?...?>} or DOCTYPE, and for
 * text a browser reads as a bogus comment are gone. These are their former negative tests: each
 * still fails to compile, at the template's file, line and column. The svg/math breakout rule
 * stays in {@code HtmlParser} (the verifier accepts a breakout tag that is never written), but the
 * verifier's message for it has the fix-it too.
 */
class ParseErrorFixItTest {
  private static final String HEAD = "<div xmlns:t=\"https://tessera.dev\" t:params=\"String x\">\n";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEAD + body + "\n</div>").compile();
  }

  private static Compilation document(String template) {
    return Compilation.builder().template("t.html", template).compile();
  }

  private static Compilation verifierOnly(String body) {
    return Compilation.builder().template("t.html", HEAD + body + "\n</div>").verifierOnly().compile();
  }

  private static String selfClosing(String pos, String name) {
    return "t.html:" + pos + ": error: a browser ignores the '/' of <" + name + "/>, because <" + name + "> isn't a void"
        + " element, and reads what follows as inside the <" + name + ">; write the end tag: <" + name + " ...></" + name
        + "> [verifier:PARSE_ERROR]";
  }

  @Test
  void selfClosingScriptStyleOrSelectIsRejected() {
    // A browser treats <script/> as an open tag, so the text after it would run as script.
    compile("<script/>[[${x}]]<script>f()</script>").assertError(selfClosing("2:1", "script"));
    compile("<p></p>\n  <SCRIPT src=\"a.js\"/>").assertError(selfClosing("3:3", "script"));
    compile("<div><style/></div>").assertError(selfClosing("2:6", "style"));
    compile("<select/><p>[[${x}]]</p>").assertError(selfClosing("2:1", "select"));
    // With a '>' in a CDATA section after it, the parser's error comes first; the verifier rejects
    // the <select/> on its own.
    verifierOnly("<div><select/><svg><![CDATA[><script>[[${x}]]</script>]]></svg></div>")
        .assertError(selfClosing("2:6", "select"));
    // Every non-void element: the same fix-it.
    compile("<div/>").assertError(selfClosing("2:1", "div"));
  }

  @Test
  void selfClosingInSvgOrMathStillCompiles() {
    // In foreign content a browser honours "/>", as Tessera's tree does.
    compile("<svg><script/><style/></svg><math><mi/></math><p>[[${x}]]</p>").assertSuccess();
  }

  @Test
  void greaterThanInAProcessingInstructionOrDoctypeIsRejected() {
    // A browser ends each at its first '>', so the <script> after it would be live.
    String pi = "error: a browser reads '<?' as the start of a comment that ends at the first '>', so the markup up to"
        + " there is hidden; HTML has no processing instructions: remove the <?...?>, or write &lt; for a literal '<'"
        + " [verifier:PARSE_ERROR]";
    compile("<?php><script>[[${x}]]</script> ?>").assertError("t.html:2:1: " + pi);
    document("<?xml version=\"1.0\" encoding=\"><script>\"?>").assertError("t.html:1:1: " + pi);
    document("<!DOCTYPE html PUBLIC \"><script>\" \"x\">")
        .assertError("t.html:1:1: error: a browser reads this <!doctype> only by error recovery (the HTML parser reports"
            + " \"“>” in public identifier.\") and ends it at its first '>'; write <!doctype html>"
            + " [verifier:PARSE_ERROR]");
  }

  @Test
  void textABrowserReadsAsABogusCommentIsRejected() {
    // A browser starts a bogus comment at "</ " that ends at the first '>', inside Tessera's
    // comment, so it would run this <script> while Tessera escaped [[${x}]] as text.
    String hidden = " as the start of a comment that ends at the first '>', so the markup up to there is hidden; ";
    String end = "error: a browser reads '</' not followed by a letter" + hidden + "write &lt; for a literal '<'"
        + " [verifier:PARSE_ERROR]";
    compile("<p>a </ b <!-- > <script> -->[[${x}]]<!-- </script> --></p>").assertError("t.html:2:6: " + end);
    compile("<p>\n  </ x></p>").assertError("t.html:3:3: " + end);
    compile("a <? b ?>").assertError("t.html:2:3: error: a browser reads '<?'" + hidden);
    for (String start : List.of("<!-x>", "<!/x>", "<!?x>", "<!!x>", "<![if !IE]>")) {
      compile("<i>a " + start + "</i>")
          .assertError("t.html:2:6: error: a browser reads '<!' that doesn't start '<!--' or '<!doctype'" + hidden
              + "write &lt; for a literal '<', or write a comment as <!-- ... --> [verifier:PARSE_ERROR]");
    }
  }

  @Test
  void rcdataAndRawTextHaveNoBogusComments() {
    compile("<textarea>Type </ html> literally</textarea><title>a <!x> <? b</title>"
        + "<script>if (a </ b) {}</script><style>p::before { content: \"<!x>\" }</style>").assertSuccess();
  }

  @Test
  void breakoutTagInSvgOrMathNamesTheFix() {
    // HtmlParser's breakout rule reports these first; the verifier rejects them on its own too.
    verifierOnly("<svg><p></p></svg>")
        .assertError("t.html:2:6: error: a browser reads <p> inside <svg> or <math> as HTML: it closes the <svg> or"
            + " <math> there and parses the rest as HTML; close the <svg> or <math> before the <p>, or put the HTML in a"
            + " <foreignObject> (in <math>, an <mtext>) [verifier:PARSE_ERROR]");
    verifierOnly("<math><div>x</div></math>").assertError("t.html:2:7: error: a browser reads <div> inside <svg>");
    verifierOnly("<svg><font color=\"red\">x</font></svg>").assertError("t.html:2:6: error: a browser reads <font>");
  }
}
