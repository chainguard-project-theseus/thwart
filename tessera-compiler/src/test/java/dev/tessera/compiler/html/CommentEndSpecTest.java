package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The compiler accepts a comment ({@code HtmlParser.earlyCommentEnd} -1) exactly when the
 * Validator.nu HTML parser reads it as one comment with the same data, ending where Tessera ends
 * it, except for a nested {@code <!--x>}: Validator.nu ends such a comment at its {@code >},
 * earlier than the WHATWG comment states and browsers do, a validator.nu artifact
 * (docs/rule-contracts.md, section 2, G10).
 */
class CommentEndSpecTest {
  private static final String ALPHABET = "-!<>a";

  /** Every {@code <!--x-->} the tokenizer delimits as one comment, for x up to {@code max} chars. */
  private static List<String> comments(int max) {
    List<String> out = new ArrayList<>();
    List<String> bodies = new ArrayList<>(List.of(""));
    for (int len = 0; len <= max; len++) {
      List<String> next = new ArrayList<>();
      for (String b : bodies) {
        String c = "<!--" + b + "-->";
        if (firstEnd(c) == c.length() - 1) {
          out.add(c);
        }
        for (char ch : ALPHABET.toCharArray()) {
          next.add(b + ch);
        }
      }
      bodies = next;
    }
    return out;
  }

  /** Where MarkupTokenizer.delimitedEnd(start, "<!---->", "--") ends a comment that starts at 0. */
  private static int firstEnd(String s) {
    for (int i = "<!---->".length() - 1; i < s.length(); i++) {
      if (s.charAt(i) == '>' && s.startsWith("--", i - 2)) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void acceptedExactlyWhenABrowserEndsTheCommentWhereTesseraDoes() {
    List<String> diffs = new ArrayList<>();
    int compared = 0;
    for (String c : comments(6)) {
      compared++;
      Node p = parse("<p>" + c + "Zq</p>").getElementsByTagName("p").item(0);
      // One comment, whose data is Tessera's, then the text: the browser ended it where Tessera does.
      boolean browserEndsThere = p.getChildNodes().getLength() == 2
          && p.getFirstChild().getNodeType() == Node.COMMENT_NODE
          && p.getFirstChild().getNodeValue().equals(c.substring(4, c.length() - 3))
          && p.getTextContent().equals("Zq");
      boolean accepted = HtmlParser.earlyCommentEnd(c) == -1;
      if (accepted != browserEndsThere && !(accepted && nestedComment(c))) {
        diffs.add(c + ": browser text after it " + p.getTextContent() + ", earlyCommentEnd "
            + HtmlParser.earlyCommentEnd(c));
      }
    }
    assertTrue(compared > 2000, "compared " + compared);
    assertEquals(List.of(), diffs.subList(0, Math.min(20, diffs.size())), diffs.size() + " mismatches");
  }

  /** A {@code <!--} after the comment's own, one char other than {@code > - !} CR and LF, and {@code >}. */
  private static boolean nestedComment(String c) {
    for (int i = 4; i + 6 <= c.length(); i++) {
      char x = c.charAt(i + 4);
      if (c.startsWith("<!--", i) && ">-!\r\n".indexOf(x) < 0 && c.charAt(i + 5) == '>') {
        return true;
      }
    }
    return false;
  }

  private static org.w3c.dom.Document parse(String html) {
    HtmlDocumentBuilder builder = new HtmlDocumentBuilder(XmlViolationPolicy.ALTER_INFOSET);
    builder.setScriptingEnabled(true);
    builder.setIgnoringComments(false);
    builder.setCommentPolicy(XmlViolationPolicy.ALLOW);
    try {
      return builder.parse(new InputSource(new StringReader(html)));
    } catch (SAXException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
