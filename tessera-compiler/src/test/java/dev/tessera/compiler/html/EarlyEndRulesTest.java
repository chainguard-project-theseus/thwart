package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The proved rule functions {@link HtmlParser#earlyEnd}, {@link HtmlParser#bogusComment} and
 * {@link HtmlParser#cdataHole} (docs/rule-contracts.md) return what their earlier {@code
 * indexOf}-based forms returned, on every string up to a length over an alphabet that covers
 * each branch.
 */
class EarlyEndRulesTest {

  private static int earlyEndBefore(String outer) {
    int gt = outer.indexOf('>');
    return gt < outer.length() - 1 ? gt : -1;
  }

  private static int cdataHoleBefore(String outer) {
    int a = outer.indexOf("[[");
    int b = outer.indexOf("[(");
    return a < 0 ? b : b < 0 ? a : Math.min(a, b);
  }

  /** The contract of bogusComment, written independently of its loop. */
  private static boolean bogusAt(String t, int i) {
    if (i + 1 >= t.length() || t.charAt(i) != '<') {
      return false;
    }
    char c = t.charAt(i + 1);
    if (c == '!' || c == '?') {
      return true;
    }
    if (c != '/') {
      return false;
    }
    if (i + 2 >= t.length()) {
      return true;
    }
    char d = t.charAt(i + 2);
    return !(d >= 'a' && d <= 'z' || d >= 'A' && d <= 'Z' || d == '>');
  }

  private static int bogusCommentSpec(String t) {
    for (int i = 0; i < t.length(); i++) {
      if (bogusAt(t, i)) {
        return i;
      }
    }
    return -1;
  }

  private static void allStrings(String alphabet, int maxLength, Consumer<String> check) {
    char[] buf = new char[maxLength];
    for (int len = 0; len <= maxLength; len++) {
      int[] digits = new int[len];
      while (true) {
        for (int i = 0; i < len; i++) {
          buf[i] = alphabet.charAt(digits[i]);
        }
        check.accept(new String(buf, 0, len));
        int i = len - 1;
        while (i >= 0 && ++digits[i] == alphabet.length()) {
          digits[i--] = 0;
        }
        if (i < 0) {
          break;
        }
      }
    }
  }

  @Test
  void earlyEndMatchesIndexOfForm() {
    allStrings(">a\"<", 8, s -> assertEquals(earlyEndBefore(s), HtmlParser.earlyEnd(s), s));
  }

  @Test
  void cdataHoleMatchesIndexOfForm() {
    allStrings("[(a]", 8, s -> assertEquals(cdataHoleBefore(s), HtmlParser.cdataHole(s), s));
  }

  @Test
  void bogusCommentMatchesContract() {
    allStrings("<!?/>aZ0 ", 5, s -> {
      assertEquals(bogusCommentSpec(s), HtmlParser.bogusComment(s), s);
      assertEquals(bogusCommentSpec(s), HtmlParser.bogusComment(new StringBuilder(s)), s);
    });
  }
}
