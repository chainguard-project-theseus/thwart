package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.model.OutputContext;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * {@link TextInlining#markupBefore}, rebuilt on the proved {@code scriptMarkupStart} and {@code
 * htmlMarkupStart} (docs/rule-contracts.md), returns what its earlier {@code lastIndexOf} and
 * {@code substring} form returned, in every context, on every string up to length 6 over an
 * alphabet that covers each branch.
 */
class MarkupBeforeRulesTest {

  private static String markupBeforeEarlier(OutputContext context, String before) {
    if (context == OutputContext.SCRIPT) {
      return before.endsWith("<!-") ? "<!-" : null;
    }
    if (context != OutputContext.TEXT && context != OutputContext.RCDATA) {
      return null;
    }
    int lt = before.lastIndexOf('<');
    if (lt < 0) {
      return null;
    }
    String end = before.substring(lt);
    if (end.equals("<") || end.equals("<!") || end.equals("<!-") || end.equals("<?")) {
      return end;
    }
    if (!end.startsWith("</")) {
      return null;
    }
    for (int i = 2; i < end.length(); i++) {
      char c = end.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
        return null;
      }
    }
    return end;
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
  void markupBeforeMatchesEarlierForm() {
    allStrings("<!-?/aZ1 ", 6, s -> {
      for (OutputContext context : OutputContext.values()) {
        assertEquals(markupBeforeEarlier(context, s), TextInlining.markupBefore(context, s), context + " " + s);
      }
    });
  }
}
