package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * {@code HtmlParser.browserEndTag}, whose JML contract restates the RCDATA, RAWTEXT and script
 * data end tag states, ends {@code <script>}, {@code <style>}, {@code <title>} and
 * {@code <textarea>} where the Validator.nu HTML parser ends them, on random content built from
 * near-miss end tags (other case, non-ASCII folds, longer names, other delimiters).
 */
class BrowserEndTagSpecTest {
  private static final String LONG_S = String.valueOf((char) 0x17F);
  private static final String DOTTED_I = String.valueOf((char) 0x130);

  private static List<String> tokens(String name) {
    String upper = name.toUpperCase(java.util.Locale.ROOT);
    return List.of("</" + name, "</" + upper, "</" + name.substring(0, 2) + LONG_S + name.substring(2),
        "</" + name.replace('i', DOTTED_I.charAt(0)), "</" + name + "x", "</" + name.substring(1), "<" + name,
        " ", ">", "/", "\t", "\n", "\f", "x", "<", "</");
  }

  @Test
  void rawTextEndsWhereABrowserEndsIt() {
    List<String> mismatches = new ArrayList<>();
    int compared = 0;
    Random random = new Random(4);
    for (String name : List.of("script", "style", "title", "textarea")) {
      List<String> tokens = tokens(name);
      for (int t = 0; t < 5000; t++) {
        StringBuilder sb = new StringBuilder("x");
        for (int k = random.nextInt(8); k >= 0; k--) {
          sb.append(tokens.get(random.nextInt(tokens.size())));
        }
        String body = sb.toString();
        String open = "<" + name + ">";
        String content = open + body + "Zq";
        int end = HtmlParser.browserEndTag(content, name, open.length());
        if (end >= 0 && end + 2 + name.length() == content.length()) {
          continue; // an end tag at the very end of the input: not a case templates reach
        }
        compared++;
        String browser = parse(content).getElementsByTagName(name).item(0).getTextContent();
        String tessera = end < 0 ? content.substring(open.length()) : content.substring(open.length(), end);
        if (!browser.equals(tessera.replace("\r", "\n"))) {
          mismatches.add(show(content) + ": browser " + show(browser) + ", browserEndTag " + show(tessera));
        }
      }
    }
    assertTrue(compared > 15000, "compared " + compared);
    assertEquals(List.of(), mismatches.subList(0, Math.min(20, mismatches.size())), mismatches.size() + " mismatches");
  }

  private static org.w3c.dom.Document parse(String html) {
    HtmlDocumentBuilder builder = new HtmlDocumentBuilder(XmlViolationPolicy.ALTER_INFOSET);
    builder.setScriptingEnabled(true);
    builder.setContentNonXmlCharPolicy(XmlViolationPolicy.ALLOW);
    builder.setContentSpacePolicy(XmlViolationPolicy.ALLOW);
    try {
      return builder.parse(new InputSource(new StringReader(html)));
    } catch (SAXException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String show(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      sb.append(c < 0x20 || c > 0x7e ? String.format("\\u%04X", (int) c) : String.valueOf(c));
    }
    return sb.append('"').toString();
  }
}
