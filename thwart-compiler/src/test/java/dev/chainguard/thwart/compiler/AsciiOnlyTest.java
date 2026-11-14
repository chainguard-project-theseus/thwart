package dev.chainguard.thwart.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Bans Java's Unicode case folding from the compiler's main sources. {@code equalsIgnoreCase},
 * {@code toLowerCase}, {@code toUpperCase} and {@code regionMatches(true, ...)} fold U+0130, U+0131,
 * U+017F and U+212A to ASCII letters, while a browser folds A-Z only in tag, attribute and keyword
 * names and URL schemes (#465, #506, #507, #519, #534). Likewise {@code Character.isWhitespace},
 * {@code trim}, {@code strip} and regex {@code \\s} count U+000B and other chars as whitespace that
 * HTML does not (#338), and {@code isLetter}/{@code isDigit} accept non-ASCII letters and digits.
 * Use {@code HtmlParser.asciiLower}, {@code asciiEqualsIgnoreCase}, {@code asciiRegionMatches},
 * {@code isHtmlWhitespace}, {@code stripHtmlWhitespace} and {@code isAscii*} (in the verifier, which
 * shares no code with the parser it checks, {@code Rules}); Java text uses {@code JavaStrings}. Java's
 * {@code CASE_INSENSITIVE} and {@code (?i)} are ASCII-only and stay allowed; the flags that make them
 * Unicode are banned.
 */
class AsciiOnlyTest {
  static final Pattern BANNED =
      Pattern.compile(
          "\\.(equalsIgnoreCase|compareToIgnoreCase|toLowerCase|toUpperCase|toTitleCase)\\("
              + "|regionMatches\\(\\s*true|CASE_INSENSITIVE_ORDER|UNICODE_CASE|UNICODE_CHARACTER_CLASS"
              + "|\\(\\?[a-zA-Z]*[uU]"
              // Unicode or Java character classes, where HTML, CSS and URLs define ASCII ones (#338)
              + "|Character\\.(isWhitespace|isSpaceChar|isLetter|isLetterOrDigit|isDigit|isAlphabetic"
              + "|isJavaIdentifierStart|isJavaIdentifierPart|isUnicodeIdentifierStart|isUnicodeIdentifierPart)\\("
              + "|\\.trim\\(\\)|\\.(strip|stripLeading|stripTrailing|isBlank)\\("
              // method references and Character.digit, which accepts non-ASCII digits
              + "|::(equalsIgnoreCase|compareToIgnoreCase|toLowerCase|toUpperCase|toTitleCase|trim|strip"
              + "|stripLeading|stripTrailing|isBlank|isWhitespace|isSpaceChar|isLetter|isLetterOrDigit|isDigit"
              + "|isAlphabetic|isJavaIdentifierStart|isJavaIdentifierPart|isUnicodeIdentifierStart"
              + "|isUnicodeIdentifierPart)\\b|Character\\.digit\\("
              // regex shorthands: \\s includes U+000B, \\b is Unicode on JDK 8; spell the ASCII set
              + "|\\\\\\\\[sSbBdDwWhHvV]|\\\\\\\\[pP]\\{");

  /** File under src/main/java, to the number of lines with a use and why they are not HTML names. */
  private static final Map<String, Allowed> ALLOWED =
      Map.of(
          "dev/chainguard/thwart/compiler/JavaStrings.java",
          new Allowed(
              13,
              "Java text: JavaBeans case, expression-language whitespace and identifiers, String.strip/trim for"
                  + " Java names, options, MessageFormat patterns and Thwart directive values"));

  private record Allowed(int lines, String reason) {}

  @Test
  void mainSourcesUseNoUnicodeCaseFoldingOrCharacterClasses() throws IOException {
    Path root = Path.of("src/main/java");
    Map<String, Integer> found = new TreeMap<>();
    StringBuilder offenders = new StringBuilder();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        String file = root.relativize(p).toString().replace('\\', '/');
        List<String> lines = read(p);
        for (int i = 0; i < lines.size(); i++) {
          if (BANNED.matcher(lines.get(i)).find()) {
            found.merge(file, 1, Integer::sum);
            if (!ALLOWED.containsKey(file)) {
              offenders.append(file).append(':').append(i + 1).append(": ").append(lines.get(i).trim()).append('\n');
            }
          }
        }
      }
    }
    assertEquals("", offenders.toString(), "Unicode case folding or character class on what may be HTML, CSS or a URL; use the ASCII helpers");
    Map<String, Integer> expected = new TreeMap<>();
    ALLOWED.forEach((file, a) -> expected.put(file, a.lines()));
    found.keySet().retainAll(expected.keySet());
    assertEquals(expected, found, "an allow-listed file gained or lost uses; review them and update ALLOWED");
  }

  @Test
  void patternCatchesEachBannedForm() {
    for (String s :
        List.of(
            "a.equalsIgnoreCase(b)",
            "s.toLowerCase(Locale.ROOT)",
            "Character.toUpperCase(c)",
            "s.regionMatches(true, 0, t, 0, 3)",
            "Pattern.compile(x, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)",
            "Pattern.compile(\"(?iu)script\")",
            "String.CASE_INSENSITIVE_ORDER",
            "Character.isWhitespace(c)",
            "Character.isLetter(c)",
            ".map(String::trim)",
            ".map(String::toLowerCase)",
            "filter(Character::isWhitespace)",
            "Character.digit(c, 16)",
            "value.trim()",
            "value.strip()",
            "Backports.strip(value)",
            "Pattern.compile(\"\\\\s*url\")",
            "Pattern.compile(\"\\\\p{L}\")")) {
      assertEquals(true, BANNED.matcher(s).find(), s);
    }
    for (String s :
        List.of(
            "Pattern.compile(\"(?i)url\")",
            "Pattern.CASE_INSENSITIVE",
            "HtmlParser.asciiLower(s)",
            "HtmlParser.stripHtmlWhitespace(s)",
            "JavaStrings.trimJava(s)",
            "sb.append(\"\\\\n\")")) {
      assertEquals(false, BANNED.matcher(s).find(), s);
    }
  }

  private static List<String> read(Path p) {
    try {
      return Files.readAllLines(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
