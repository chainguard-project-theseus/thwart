package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HTML whitespace is TAB, LF, FF, CR and SPACE; Java's {@code Character.isWhitespace}, {@code trim},
 * {@code strip} and regex {@code \\s} also count U+000B and more (#338). Keyword matches that only add
 * checks strip more than any browser ({@link HtmlParser#stripAnySpace}).
 */
class AsciiWhitespaceTest {
  private static final String HEADER = "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  @Test
  void helpers() {
    for (char c : "\t\n\f\r ".toCharArray()) {
      assertTrue(HtmlParser.isHtmlWhitespace(c));
    }
    for (char c : "\u000b\u001c\u0085\u00a0\u2000\u2028\u3000".toCharArray()) {
      assertFalse(HtmlParser.isHtmlWhitespace(c), Integer.toHexString(c));
    }
    assertEquals("\u000bx\u2000", HtmlParser.stripHtmlWhitespace(" \t\u000bx\u2000\r\n"));
    assertEquals("x", HtmlParser.stripAnySpace("\u0001\u000b\u00a0\u2000x\u3000\u2028 "));
    assertFalse(HtmlParser.isAsciiAlpha('\u00e9'));
    assertFalse(HtmlParser.isAsciiDigit('\u0663'));
  }

  @Test
  void httpEquivMatchesThroughAnySpace() {
    // An engine that strips Unicode spaces reads these as refresh; the page must not take a computed URL.
    for (String w : List.of("\u00a0", "\u2000", "\u3000")) {
      compile("<meta http-equiv=\"" + w + "refresh" + w + "\" t:content=\"${s}\">")
          .assertError("t:content is not allowed");
    }
  }

  @Test
  void scriptTypeStripsOnlyHtmlWhitespace() {
    // A browser strips ASCII whitespace from type; anything else makes an inert data block, which
    // Tessera does not write into. A match here turns FORBIDDEN into SCRIPT, so it must not over-strip.
    for (String type : List.of("\u00a0", "\u0001text/javascript", "\u000btext/javascript", "\u2000text/javascript",
        "\u00a0application/json")) {
      compile("<script type=\"" + type + "\">var a = [[${s}]];</script>").assertError("not JavaScript");
    }
    assertEquals(
        "<div><script type=\" text/javascript\t\">var a = \"x\";</script></div>",
        compile("<script type=\" text/javascript\t\">var a = [[${s}]];</script>").assertSuccess()
            .render("t", Map.of("s", "x")));
  }

  @Test
  void numericReferencesNeedAsciiDigits() {
    // Character.digit accepts Arabic-Indic and fullwidth digits; a browser leaves such a reference as text.
    assertEquals("pos&#\u0661\u0661\u0666;", HtmlParser.decode("pos&#\u0661\u0661\u0666;"));
    assertEquals("pos&#x\uff17\uff14;", HtmlParser.decode("pos&#x\uff17\uff14;"));
    assertEquals("post", HtmlParser.decode("pos&#116;"));
    assertEquals("post", HtmlParser.decode("pos&#x74;"));
    assertEquals("post", HtmlParser.decode("pos&#X74;"));
    assertEquals("posJ", HtmlParser.decode("pos&#x4a;"));
  }

  @Test
  void refreshPrefixSkipsOnlyHtmlWhitespace() {
    // Java's \\s matched U+000B, which a browser does not skip before url=.
    compile("<meta http-equiv=\"refresh\" t:content=\"'3;\u000burl=' + @{/a}\">").assertError("t:content is not allowed");
    assertEquals(
        "<div><meta http-equiv=\"refresh\" content=\"3;url=/a\"></div>",
        compile("<meta http-equiv=\"refresh\" t:content=\"'3; url=' + @{/a}\">").assertSuccess().render("t", Map.of()));
  }

  @Test
  void attrSettingFormIsFoundAsTheExpressionParserReadsIt() {
    // t:attr skips Java whitespace before a name, so U+2000form= sets form: the button submits the token
    // form, and its formaction is checked. hasFormAttribute now reads the parsed names, not a regex.
    compile("<form method=\"post\" id=\"f\" action=\"/a\"><input name=\"q\"></form>"
            + "<button t:attr=\"\u2000form=${s}\" formaction=\"https://other.example/\">b</button>")
        .assertError("formaction=\"https://other.example/\" is not allowed here");
  }

  @Test
  void linkHostVariableFoundThroughAnySpace() {
    // U+2000 is already a whitespace error in a link path; NBSP reaches the host check.
    compile("<a t:href=\"@{https:\u00a0//{s}/x(s=${s})}\">x</a>").assertError("path variable in its host");
  }

  @Test
  void tagsSplitOnlyOnHtmlWhitespace() {
    for (String w : List.of("\u000b", "\u001c", "\u2000", "\u3000")) {
      compile("<p" + w + "title=\"x\">y</p>").assertError("in a tag is not HTML whitespace");
    }
  }
}
