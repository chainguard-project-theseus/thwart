package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * An inlined value right after a static {@code <} would continue it into markup: the tokenizer
 * reads {@code <[[s]]} as text, but with {@code s = "img src=x onerror=alert(1)"} a browser reads
 * an {@code <img>} (HTML text escaping leaves letters, spaces, {@code =} and parentheses alone).
 * Such holes are build errors; a {@code &lt;} keeps the {@code <} text. (A {@code <} and a space
 * stays text too, but it is a parse error, which the verifier rejects since #406.)
 */
class ValueAfterLessThanTest {

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"String s, int n\">\n" + body + "\n</div>")
        .compile();
  }

  private static String render(String body, String s, int n) {
    String out = compile(body).assertSuccess().render("page", Map.of("s", s, "n", n));
    return out.substring("<div>\n".length(), out.length() - "\n</div>".length());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "<p><[[s]]></p>|7|<",
        "<p>a<[[s]]</p>|8|<",
        "<p></[[s]]></p>|8|</",
        "<p><![[s]]></p>|8|<!",
        "<p><!-[[s]]></p>|9|<!-",
        "<p>[[s]]<[[s]]></p>|12|<",
        "<textarea></[[s]]></textarea>|15|</",
        "<textarea><[[s]]></textarea>|14|<",
        "<title></[[s]]></title>|12|</",
        "<p><[(s)]></p>|7|<",
        "<textarea></text[[s]]area></textarea>|19|</text",
        "<textarea></textarea[[s]]></textarea>|23|</textarea",
        "<title></title[[s]]></title>|17|</title",
      })
  void valueAfterMarkupStartFailsToBuild(String body, int column, String markup) {
    String form = body.contains("[(") ? "[(...)]" : "[[...]]";
    compile(body)
        .assertError(
            "page.html:2:" + column + ":",
            form + " may not follow '" + markup + "' directly: the browser would read the value as markup;"
                + " write &lt; for a literal '<'");
  }

  @Test
  void emptyValueCannotJoinStaticTextIntoATag() {
    // With s = "", "<[[s]]script>" would render "<script>".
    compile("<p><[[s]]script>alert(1)<[[s]]/script></p>")
        .assertError("page.html:2:7:", "[[...]] may not follow '<' directly");
  }

  @Test
  void valueInsideATagNameFailsToBuild() {
    // In element text "<" or "</" and a letter starts a tag, so the hole is part of its name.
    compile("<scr[[s]]ipt>alert(1)</scr[[s]]ipt>").assertError("page.html:2:1:", "invalid element name 'scr[[s]]ipt'");
  }

  @Test
  void messageEndingInLessThanFailsToBuild() {
    Compilation.builder()
        .template("page.html", "<div t:params=\"String s\">\n<p>[(#{prefix})][[s]]></p>\n</div>")
        .messages("prefix=<\n")
        .compile()
        .assertError("page.html:2:", "message 'prefix'", "a message may not end with '<'");
  }

  @Test
  void negativeNumberAfterBangDashInScriptFailsToBuild() {
    // n = -1 renders "<!--1", and a browser then hides the "</script>" after "<script". The data-prelude
    // rule (#332) rejects the "<!" before the value.
    compile("<script>\nvar x = 2 <!-[[n]]\nvar y = \"<script>\";\n</script>")
        .assertError("page.html:3:11:", "'<!' (a value or code after it can make '<!--', an HTML-like comment)");
  }

  @Test
  void lessThanThatStaysTextStillCompiles() {
    String s = "img src=x onerror=alert(1)";
    assertEquals("<p>&lt;img src=x onerror=alert(1)></p>", render("<p>&lt;[[s]]></p>", s, 0));
    for (String parseError : List.of("<p>a < [[s]] b</p>", "<p>[[s]]< b</p>")) {
      compile(parseError).assertError("page.html:2:", "Unescaped \u201C<\u201D", "[verifier:PARSE_ERROR]");
    }
    assertEquals("<p>a<b>img src=x onerror=alert(1)</b></p>", render("<p>a<b>[[s]]</b></p>", s, 0));
    assertEquals("<p>&lt;img src=x onerror=alert(1)</p>", render("<p t:text=\"|<${s}|\">x</p>", s, 0));
    assertEquals(
        "<p title=\"&lt;img src=x onerror=alert(1)\">x</p>", render("<p title=\"<\" t:title=\"'<' + s\">x</p>", s, 0));
    assertEquals("<textarea>&lt;img src=x onerror=alert(1)</textarea>", render("<textarea>&lt;[[s]]</textarea>", s, 0));
    assertEquals("<textarea></text-area></textarea>", render("<textarea></text-[[s]]></textarea>", "area", 0));
  }

  @Test
  void comparisonsInScriptStillCompile() {
    assertEquals("<script>if (1<2) {}</script>", render("<script>if (1<[[n]]) {}</script>", "", 2));
    assertEquals("<script>var b = 1 < !-2;</script>", render("<script>var b = 1 < !-[[n]];</script>", "", 2));
  }
}
