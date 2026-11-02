package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Value text reaches element text through one function, {@link Escapers#appendText}, which never
 * lets two braces meet (#248, #336). A new runtime path that escapes or wraps text another way
 * fails here and has to be reviewed: add it to an allow-list only if it never writes element text.
 */
class TextChokePointTest {

  private static final Pattern METHOD =
      Pattern.compile("^  (?=\\S)(?:public |private |protected )?(?:static )?(?:final )?[\\w<>\\[\\], .]+ (\\w+)\\(");

  /** Methods (as {@code Class.method}) that may call the attribute escaper: they write attribute values or RCDATA. */
  private static final Set<String> ATTRIBUTE_ESCAPER_CALLERS =
      Set.of(
          "Escapers.rcdata", "Escapers.attr", "Escapers.refresh", "Escapers.escapeHtml",
          "HtmlSanitizer.url", "HtmlSanitizer.textAttribute");

  /** The arguments {@code new SafeHtml(...)} may be built from. */
  private static final Set<String> SAFE_HTML_SOURCES =
      Set.of(
          "\"\"", "html", "Escapers.escapeText(text == null ? \"\" : text)", "HtmlSanitizer.sanitize(html, policy)",
          "sb.toString()", "requireFlowPartial(template, \"SafeHtml.of\").renderToString(ctx)");

  @Test
  void onlyTheChokePointBreaksBraces() throws IOException {
    assertEquals(Set.of("Escapers.appendEscapedHtml", "Escapers.rawEdges"), callers("BRACE_BREAK", true));
  }

  @Test
  void attributeEscaperIsCalledOnlyForAttributesAndRcdata() throws IOException {
    Set<String> callers = callers("(?<![\\w.])(?:Escapers\\.)?(?:escapeHtml|appendHtml)\\(", false);
    callers.removeAll(ATTRIBUTE_ESCAPER_CALLERS);
    assertEquals(Set.of(), callers);
  }

  @Test
  void safeHtmlIsBuiltOnlyFromCheckedSources() throws IOException {
    Set<String> args = new TreeSet<>();
    Matcher m = Pattern.compile("new SafeHtml\\((.*)\\);").matcher(Files.readString(source("SafeHtml")));
    while (m.find()) {
      args.add(m.group(1));
    }
    args.removeAll(SAFE_HTML_SOURCES);
    assertEquals(Set.of(), args);
  }

  /** Every text path, next to a brace on either side and to itself, never forms {@code {{}. */
  @Test
  void noTextPathFormsDoubleBraces() {
    RenderContext ctx = new RenderContext(Locale.ROOT, (k, a) -> k + (a.length > 0 ? a[0] : ""), new DefaultLinks(""), Forms.NONE);
    List<Function<String, String>> paths = new ArrayList<>();
    paths.add(s -> render(out -> Escapers.text(out, s), true));
    paths.add(s -> render(out -> Escapers.text(out, s), false));
    paths.add(s -> SafeHtml.escape(s).html());
    paths.add(s -> SafeHtml.sanitize(s).html());
    paths.add(s -> SafeHtml.sanitize(s, HtmlPolicy.RICH).html());
    paths.add(s -> Escapers.htmlMessage(ctx, "{", new Object[] {s}, new String[0]));
    // Markup the build checked or the caller vouches for: only its edges are the runtime's job.
    List<Function<String, String>> raw = new ArrayList<>();
    raw.add(s -> render(out -> Escapers.html(out, TestSafeHtml.of(s)), true));
    raw.add(s -> Escapers.htmlMessage(ctx, s, new Object[0], new String[] {s}));
    String[] values = {"{", "{{", "a{", "{a", "{{x}}", "&#123;&#123;", "&lbrace;{", "<b>{</b>{", "{<b>x</b>{", "}}", "}", "a}", "{}"};
    String[] markup = {"{", "a{", "{a", "<b>{</b>{", "{<b>x</b>{", "}}"};
    check(paths, values, true);
    check(raw, markup, false);
  }

  /** Every spelling of a character reference a browser decodes to '{'. */
  private static final Pattern BRACE_REF = Pattern.compile("&(?:#0*123(?![0-9]);?|#[xX]0*7[bB](?![0-9a-fA-F]);?|lbrace;|lcub;)");

  /** With {@code close}, a '}' never meets another one either (#571: every brace of a value stands alone). */
  private static void check(List<Function<String, String>> paths, String[] values, boolean close) {
    for (Function<String, String> path : paths) {
      for (String v : values) {
        String out = path.apply(v);
        for (String around : new String[] {"{" + out + "{", out + out, "}" + out + "}"}) {
          // As the framework reads the text: brace references decoded.
          String decoded = BRACE_REF.matcher(around).replaceAll("{");
          assertFalse(decoded.contains("{{") || close && decoded.contains("}}"), v + " -> " + around);
        }
      }
    }
  }

  private static String render(java.util.function.Consumer<Output> write, boolean stringOutput) {
    if (stringOutput) {
      StringOutput out = new StringOutput();
      write.accept(out);
      return out.toString();
    }
    StringWriter w = new StringWriter();
    write.accept(new WriterOutput(w));
    return w.toString();
  }

  /** {@code Class.method} of every runtime method whose body matches {@code regex}. */
  private static Set<String> callers(String regex, boolean skipDeclaration) throws IOException {
    Pattern p = Pattern.compile(regex);
    Set<String> out = new TreeSet<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/tessera/runtime"))) {
      for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
        String cls = f.getFileName().toString().replace(".java", "");
        String method = null;
        for (String line : Files.readAllLines(f)) {
          Matcher m = METHOD.matcher(line);
          if (m.find() && !line.contains(" new ") && !line.contains(" = ")) {
            method = m.group(1);
          }
          String code = line.trim();
          if (code.startsWith("*") || code.startsWith("/**") || code.startsWith("//")) {
            continue;
          }
          if (skipDeclaration && code.startsWith("static final String BRACE_BREAK")) {
            continue;
          }
          if (p.matcher(code).find() && !METHOD.matcher(line).find()) {
            out.add(cls + "." + method);
          }
        }
      }
    }
    return out;
  }

  private static Path source(String cls) {
    return Path.of("src/main/java/dev/tessera/runtime/" + cls + ".java");
  }
}
