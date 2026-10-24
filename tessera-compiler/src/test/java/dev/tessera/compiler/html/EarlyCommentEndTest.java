package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code HtmlParser.earlyCommentEnd} runs the WHATWG comment states (its JML contract). It still
 * gives what its {@code startsWith}/{@code indexOf} form gave on every comment the tokenizer can
 * hand it.
 */
class EarlyCommentEndTest {
  private static final String ALPHABET = "-!<>a";

  /** Every {@code <!--x-->} the tokenizer delimits as one comment, for x up to {@code max} chars. */
  private static List<String> comments(int max) {
    List<String> out = new ArrayList<>();
    List<String> bodies = new ArrayList<>(List.of(""));
    for (int len = 0; len <= max; len++) {
      List<String> next = new ArrayList<>();
      for (String b : bodies) {
        String c = "<!--" + b + "-->";
        // The tokenizer ends a comment at the first "-->" whose '>' is at least 7 chars in.
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

  /** The form before the WHATWG state machine replaced it. */
  private static int before(String comment) {
    if (comment.startsWith("<!-->") || comment.startsWith("<!--->")) {
      return 0;
    }
    return comment.indexOf("--!>", 4);
  }

  @Test
  void sameResultAsBeforeOnEveryTokenizerComment() {
    List<String> comments = comments(7);
    List<String> diffs = new ArrayList<>();
    for (String c : comments) {
      if (HtmlParser.earlyCommentEnd(c) != before(c)) {
        diffs.add(c + ": " + HtmlParser.earlyCommentEnd(c) + " vs " + before(c));
      }
    }
    assertTrue(comments.size() > 10000, "comments " + comments.size());
    assertEquals(List.of(), diffs.subList(0, Math.min(20, diffs.size())), diffs.size() + " differences");
  }

  @Test
  void sameResultAsBeforeOnEveryShortInput() {
    // Every "<!--" + x with x up to 3 chars, delimited by the tokenizer or not: <!--> and <!---> give 0.
    List<String> inputs = new ArrayList<>(List.of("<!--"));
    List<String> previous = List.of("<!--");
    for (int len = 1; len <= 3; len++) {
      List<String> next = new ArrayList<>();
      for (String s : previous) {
        for (char ch : ALPHABET.toCharArray()) {
          next.add(s + ch);
        }
      }
      inputs.addAll(next);
      previous = next;
    }
    List<String> diffs = new ArrayList<>();
    for (String c : inputs) {
      if (HtmlParser.earlyCommentEnd(c) != before(c)) {
        diffs.add(c + ": " + HtmlParser.earlyCommentEnd(c) + " vs " + before(c));
      }
    }
    assertEquals(1 + 5 + 25 + 125, inputs.size());
    assertEquals(0, HtmlParser.earlyCommentEnd("<!-->"));
    assertEquals(0, HtmlParser.earlyCommentEnd("<!--->"));
    assertEquals(List.of(), diffs);
  }
}
