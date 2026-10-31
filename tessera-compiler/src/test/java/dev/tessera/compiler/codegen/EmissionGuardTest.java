package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The skeleton is only exact if every byte generated code writes goes through {@link Emitted}
 * (#366). This scans the string literals of the compiler's main sources, the text generated code
 * is built from, and fails on any that writes template output outside the short allow-list below:
 * {@code out.write}, an {@code Escapers} output method, a fragment {@code render(ctx, out)} call,
 * or the output variable {@code out} passed as an argument.
 */
class EmissionGuardTest {
  private static final Path MAIN = Path.of("src/main/java/dev/tessera/compiler");

  /** Text in a generated-code literal that writes output. */
  private static final List<Pattern> WRITES =
      List.of(
          Pattern.compile("\\bout\\s*\\.\\s*write\\b"),
          // The output variable used in any other way: passed on, aliased (Output o = out), ...
          // but not declared (Output out) and not the English word (out of the table).
          Pattern.compile("(?<![\\w$.])(?<!Output\\s)out(?![\\w$])(?!\\s+of\\b)"),
          Pattern.compile("\\bEscapers\\.(text|rcdata|attr|url|resourceUrl|refresh|html|json|jsonAttr)\\s*\\("),
          Pattern.compile("\\.(text|rcdata|attr|url|resourceUrl|refresh|html|json|jsonAttr)\\s*\\(\\s*(out\\b|$)"),
          Pattern.compile("\\.render\\s*\\("));

  /**
   * Code (outside literals) that takes the Java text out of an {@link Emitted} without its skeleton:
   * {@code Emitted.code(e.java())} or a wrapper string built from it would write unrecorded bytes.
   */
  private static final Pattern UNRECORDED = Pattern.compile("\\.(java|open)\\(\\)");

  /**
   * Methods allowed to write, as {@code file#method}, each with why it records nothing. Everything
   * in {@code codegen/Emitted.java} is allowed: it is the emitter.
   */
  private static final Set<String> ALLOWED =
      Set.of(
          // The instance render() of a template class calls its static render(params..., ctx, out),
          // whose body is the recorded one.
          "codegen/TemplateClassSource.java#render",
          // The registry's adapters call a template class's render: the template's whole skeleton.
          "codegen/RegistryGenerator.java#adapter",
          // Names the parameters of a Fragment lambda: it writes nothing where it is created, and
          // the skeleton of what it writes is recorded as the fragment argument (Skeleton.Call).
          "codegen/attr/InsertReplaceProcessor.java#fragmentValue",
          // Take an Emitted's Java whose skeleton is recorded separately: the template's render
          // body (TemplateSkeleton.body), each fragment method (TemplateSkeleton.fragments) and a
          // ~{::tag} selection helper (the fragment argument's skeleton).
          "codegen/TemplateGenerator.java#generate",
          "codegen/attr/FragmentMethods.java#generate",
          "codegen/attr/ElementSelection.java#helperCall",
          // Wrapper.toString, for error messages.
          "codegen/ElementPlan.java#toString");

  /** A literal that writes output, outside the allow-list. */
  record Finding(String file, int line, String method, String literal) {
    @Override
    public String toString() {
      return file + ":" + line + " (" + method + "): \"" + literal + "\"";
    }
  }

  @Test
  void noGeneratedCodeWritesOutsideTheEmitter() throws IOException {
    List<Finding> findings = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
        String rel = MAIN.relativize(p).toString().replace('\\', '/');
        if (rel.equals("codegen/Emitted.java")) {
          continue;
        }
        for (Finding f : scan(rel, Files.readString(p, StandardCharsets.UTF_8))) {
          if (!ALLOWED.contains(f.file() + "#" + f.method())) {
            findings.add(f);
          }
        }
      }
    }
    assertTrue(
        findings.isEmpty(),
        "generated code writes output outside codegen/Emitted, so the skeleton would not record it; build it"
            + " with Emitted:\n" + findings.stream().map(Finding::toString).collect(Collectors.joining("\n")));
  }

  @Test
  void theAllowListIsNotStale() throws IOException {
    for (String a : ALLOWED) {
      String file = a.substring(0, a.indexOf('#'));
      String method = a.substring(a.indexOf('#') + 1);
      List<Finding> f = scan(file, Files.readString(MAIN.resolve(file), StandardCharsets.UTF_8));
      assertTrue(f.stream().anyMatch(x -> x.method().equals(method)), "allow-list entry matches nothing: " + a);
    }
  }

  @Test
  void flagsEveryWayOfWriting() {
    String src =
        """
        class X {
          private static String a(String v) {
            return "out.write(\\"<b>\\");";
          }
          static String b(String v) {
            return "dev.tessera.runtime.Escapers.text(out, " + v + ");";
          }
          String c(String v) {
            return ESCAPERS + ".attr(" + "out" + ", " + v + ")";
          }
          public static String d(String f) {
            return f + ".render(ctx, out);";
          }
          static String e(String f) {
            return f + "(x, ctx, out);"; // a fragment method
          }
          static String g(String code) {
            return code + ";\\nout.write(s$.toString());\\n}\\n";
          }
          static Emitted h(Emitted e) {
            return Emitted.code(e.java()); // unrecorded
          }
          static String i(String v) {
            return "dev.tessera.runtime.Output o$ = out; o$.write(" + v + ");";
          }
          static String fine(String v) {
            // out.write in a comment, "out" of the table in a message
            return "dev.tessera.runtime.Escapers.toText(" + v + ") + \\" it goes out of the table\\"";
          }
        }
        """;
    List<Finding> f = scan("X.java", src);
    assertEquals(
        List.of("a", "b", "c", "c", "d", "e", "g", "h", "i"), f.stream().map(Finding::method).collect(Collectors.toList()), f.toString());
  }

  private static final Pattern METHOD =
      Pattern.compile(
          "^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\\s+)*"
              + "(?:<[^>]*>\\s*)?[\\w.$<>\\[\\],?]+(?:\\s*<[^()]*>)?\\s+(\\w+)\\s*\\([^;]*$");
  private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "return", "new", "else");

  /** The string literals of {@code src} that write output, with the method each is in. */
  static List<Finding> scan(String file, String src) {
    List<Finding> out = new ArrayList<>();
    String method = "";
    int line = 1;
    int lineStart = 0;
    int i = 0;
    int n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == '\n') {
        line++;
        lineStart = i + 1;
        int end = src.indexOf('\n', lineStart);
        String l = src.substring(lineStart, end < 0 ? n : end);
        Matcher m = METHOD.matcher(l);
        String t = l.trim();
        if (m.find() && !NOT_METHODS.contains(m.group(1))
            && !t.startsWith("return ") && !t.startsWith("new ") && !t.startsWith("throw ") && !t.startsWith("else ")) {
          method = m.group(1);
        }
        i++;
      } else if (src.startsWith("//", i)) {
        int end = src.indexOf('\n', i);
        i = end < 0 ? n : end;
      } else if (src.startsWith("/*", i)) {
        int end = src.indexOf("*/", i + 2);
        for (int k = i; k < (end < 0 ? n : end); k++) {
          if (src.charAt(k) == '\n') {
            line++;
          }
        }
        i = end < 0 ? n : end + 2;
      } else if (src.startsWith("\"\"\"", i)) {
        int end = src.indexOf("\"\"\"", i + 3);
        String lit = src.substring(i + 3, end < 0 ? n : end);
        check(out, file, line, method, lit);
        line += (int) lit.chars().filter(ch -> ch == '\n').count();
        i = end < 0 ? n : end + 3;
      } else if (c == '"' || c == '\'') {
        int k = i + 1;
        StringBuilder lit = new StringBuilder();
        while (k < n && src.charAt(k) != c && src.charAt(k) != '\n') {
          if (src.charAt(k) == '\\' && k + 1 < n) {
            lit.append(src.charAt(k)).append(src.charAt(k + 1));
            k += 2;
          } else {
            lit.append(src.charAt(k++));
          }
        }
        if (c == '"') {
          check(out, file, line, method, lit.toString());
        }
        i = k + 1;
      } else {
        Matcher m = UNRECORDED.matcher(src).region(i, Math.min(n, i + 8));
        if (m.lookingAt()) {
          out.add(new Finding(file, line, method, "<code> " + m.group()));
          i = m.end();
        } else {
          i++;
        }
      }
    }
    return out;
  }

  private static void check(List<Finding> out, String file, int line, String method, String literal) {
    String text = unescape(literal);
    for (Pattern p : WRITES) {
      if (p.matcher(text).find()) {
        out.add(new Finding(file, line, method, literal));
        return;
      }
    }
  }

  /** The value of a Java string literal's content (backslash escapes decoded, roughly). */
  private static String unescape(String literal) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < literal.length(); i++) {
      char c = literal.charAt(i);
      if (c == '\\' && i + 1 < literal.length()) {
        char e = literal.charAt(++i);
        sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
