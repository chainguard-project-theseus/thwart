package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every text run {@link MarkupTokenizer#run} hands the handler has no {@code <} that a browser
 * would start a tag at: none followed by an ASCII letter, none followed by {@code /} and an ASCII
 * letter or {@code >} (the JML contract of {@code nextStructure} and {@code kind},
 * docs/rule-contracts.md section 6). Checked on every input up to length 6 over an alphabet that
 * covers each branch of {@code kind}; inputs the tokenizer rejects are skipped.
 */
class TextRunSpecTest {
  private static final String ALPHABET = "<!/?aZ>- ";

  private static boolean letter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  /** The contract's noTagStart, over the whole input. */
  private static boolean noTagStart(String s, int j) {
    return !(j + 1 < s.length() && letter(s.charAt(j + 1)))
        && !(j + 2 < s.length() && s.charAt(j + 1) == '/' && (letter(s.charAt(j + 2)) || s.charAt(j + 2) == '>'));
  }

  private static List<int[]> textRuns(String s) throws MarkupTokenizer.MarkupException {
    List<int[]> runs = new ArrayList<>();
    new MarkupTokenizer(s.toCharArray(), new MarkupTokenizer.Handler() {
      @Override
      public void text(int start, int end) {
        runs.add(new int[] {start, end});
      }

      @Override
      public void comment(int start, int end) {}

      @Override
      public void cdata(int start, int end) {}

      @Override
      public void declaration(int start, int end) {}

      @Override
      public void startTag(int lt, int nameEnd) {}

      @Override
      public void attribute(int nameStart, int nameEnd, int valueStart, int valueEnd, char quote) {}

      @Override
      public void openTagEnd(int gt) {}

      @Override
      public void selfClosedTagEnd(boolean minimized) {}

      @Override
      public void endTag(int lt, int nameEnd) {}

      @Override
      public boolean rawText() {
        return false;
      }
    }).run();
    return runs;
  }

  @Test
  void noTextRunHasALessThanThatStartsATag() {
    List<String> violations = new ArrayList<>();
    int tokenized = 0;
    int withLt = 0;
    char[] buf = new char[6];
    for (int len = 0; len <= buf.length; len++) {
      int[] digits = new int[len];
      while (true) {
        for (int i = 0; i < len; i++) {
          buf[i] = ALPHABET.charAt(digits[i]);
        }
        String s = new String(buf, 0, len);
        try {
          for (int[] run : textRuns(s)) {
            tokenized++;
            for (int j = run[0]; j < run[1]; j++) {
              if (s.charAt(j) == '<') {
                withLt++;
                if (!noTagStart(s, j)) {
                  violations.add("\"" + s + "\" run " + run[0] + ".." + run[1] + " at " + j);
                }
              }
            }
          }
        } catch (MarkupTokenizer.MarkupException e) {
          // rejected: the compiler reports it
        }
        int i = len - 1;
        while (i >= 0 && ++digits[i] == ALPHABET.length()) {
          digits[i--] = 0;
        }
        if (i < 0) {
          break;
        }
      }
    }
    assertTrue(tokenized > 10000 && withLt > 10000, "runs " + tokenized + ", '<' in runs " + withLt);
    assertEquals(List.of(), violations.subList(0, Math.min(20, violations.size())), violations.size() + " violations");
  }

  @Test
  void aTagStartIsNotText() throws MarkupTokenizer.MarkupException {
    // The negative side: these '<' start markup, so no text run contains them.
    for (String s : List.of("x<a>y", "x</a>y", "x<Z/>y")) {
      for (int[] run : textRuns(s)) {
        assertEquals(-1, s.substring(run[0], run[1]).indexOf('<'), s);
      }
    }
    // A run ends at the first '<' that starts markup, not at the first '<'.
    List<int[]> runs = textRuns("< <a>");
    assertEquals(1, runs.size());
    assertEquals(List.of(0, 2), List.of(runs.get(0)[0], runs.get(0)[1]));
  }
}
