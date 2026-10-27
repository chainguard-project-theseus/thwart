package dev.tessera.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every diagnostic message in the compiler appears in the catalogue of {@code docs/guide.md}.
 *
 * <p>The test collects the string literals passed to every diagnostic call ({@code error},
 * {@code warning}, {@code printMessage}, ...) in {@code tessera-compiler/src/main/java}, plus the
 * reason and hint texts that are spliced into messages, and checks that each literal piece of 12
 * characters or more appears verbatim (whitespace collapsed) in the guide. Dynamic parts are
 * written as {@code <placeholders>} in the guide, so only the fixed text has to match.
 */
class DiagnosticCatalogueTest {
  private static final Path SOURCES = Path.of("src/main/java/dev/tessera/compiler");
  private static final Path GUIDE = Path.of("../docs/guide.md");
  private static final int MIN_PIECE = 12;

  /** Calls whose arguments are (parts of) diagnostic messages. */
  private static final Pattern CALL =
      Pattern.compile("\\b(error|errorType|warning|printMessage|ParseError|objectMethodError|Optional\\.of)\\s*\\(");

  /** Code regions whose strings are spliced into messages: {file suffix, start marker, end marker}. */
  private static final String[][] REGIONS = {
    {"GenericAttrProcessor.java", "String hint;", "hint = null;"},
    {"TextInlining.java", "static String where(", "\n  }"},
    {"HtmlParser.java", "static String earlyEndMessage(", "\n  }"},
    {"HtmlParser.java", "static String bogusCommentMessage(", "\n  }"},
  };

  private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

  @Test
  void everyDiagnosticAppearsInTheGuide() throws IOException {
    String guide = normalize(Files.readString(GUIDE));
    List<String> missing = new ArrayList<>();
    Set<String> matchedRegions = new TreeSet<>();
    int checked = 0;
    try (Stream<Path> files = Files.walk(SOURCES)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        String src = Files.readString(f);
        for (Piece piece : pieces(f, src, matchedRegions)) {
          checked++;
          if (!guide.contains(piece.text())) {
            missing.add(piece.where() + ": " + piece.text());
          }
        }
      }
    }
    assertEquals(
        new TreeSet<>(Arrays.stream(REGIONS).map(r -> r[0]).toList()),
        matchedRegions,
        "REGIONS entries whose file was not found under " + SOURCES + "; update REGIONS");
    assertTrue(checked > 150, "found only " + checked + " message pieces; is the extraction broken?");
    assertTrue(missing.isEmpty(), "diagnostic text missing from docs/guide.md:\n" + String.join("\n", missing));
  }

  record Piece(String where, String text) {}

  /** Message pieces in {@code file}; adds the file suffix of every REGIONS entry it matches. */
  private static List<Piece> pieces(Path file, String src, Set<String> matchedRegions) {
    List<Piece> out = new ArrayList<>();
    Matcher m = CALL.matcher(src);
    while (m.find()) {
      boolean optional = m.group(1).equals("Optional.of");
      if (optional && !file.endsWith("ViewSafety.java")) {
        continue;
      }
      int end = closing(src, m.end());
      literals(file, src, m.end(), end, out);
    }
    for (String[] r : REGIONS) {
      if (file.toString().endsWith(r[0])) {
        matchedRegions.add(r[0]);
        int start = src.indexOf(r[1]);
        if (start < 0) {
          throw new AssertionError(
              "region marker \"" + r[1] + "\" not found in " + file.getFileName() + "; update REGIONS");
        }
        int end = src.indexOf(r[2], start);
        if (end < 0) {
          throw new AssertionError(
              "region end marker after \"" + r[1] + "\" not found in " + file.getFileName() + "; update REGIONS");
        }
        literals(file, src, start, end, out);
      }
    }
    return out;
  }

  /** Index of the ')' closing the call whose arguments start at {@code from}. */
  private static int closing(String src, int from) {
    int depth = 1;
    for (int i = from; i < src.length(); i++) {
      char c = src.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipLiteral(src, i, c);
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return src.length();
  }

  private static int skipLiteral(String src, int i, char quote) {
    for (int j = i + 1; j < src.length(); j++) {
      char c = src.charAt(j);
      if (c == '\\') {
        j++;
      } else if (c == quote) {
        return j;
      }
    }
    return src.length();
  }

  private static void literals(Path file, String src, int from, int to, List<Piece> out) {
    Matcher s = STRING.matcher(src).region(from, to);
    while (s.find()) {
      String text = normalize(unescape(s.group(1))).strip();
      if (text.length() >= MIN_PIECE && text.contains(" ")) {
        int line = (int) src.substring(0, s.start()).chars().filter(c -> c == '\n').count() + 1;
        out.add(new Piece(SOURCES.relativize(file) + ":" + line, text));
      }
    }
  }

  private static String unescape(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }

  private static String normalize(String s) {
    return s.replaceAll("\\s+", " ");
  }
}
