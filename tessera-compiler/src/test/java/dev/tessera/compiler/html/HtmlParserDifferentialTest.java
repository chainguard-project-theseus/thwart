package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.model.Document;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Parses a corpus with {@link HtmlParser} (Tessera's own tokenizer) and with {@link
 * AttoparserHtmlParser} (the attoparser-based parser it replaced, #170) and requires the same
 * documents (node kinds, names, attributes, quotes, positions, text contexts, self-closing and
 * end tags) and the same diagnostics, character for character, up to where Tessera rejects a
 * tag that a browser reads differently (a quote outside a quoted attribute value, a char that is
 * whitespace in Java but not in HTML, or a misplaced {@code /}).
 *
 * <p>Corpus: every {@code .html} file in the repository (outside {@code bench/}), every fenced
 * block in the docs, every string literal containing {@code <} in the repository's test sources,
 * hand-picked edge cases, and seeded mutations and random token soups of all of those, so failures
 * reproduce. A regression test for the switch; it can be deleted once attoparser is long gone.
 */
class HtmlParserDifferentialTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final long SEED = 170;
  /** Tessera's own fatal tag errors (see {@code MarkupTokenizer.tagEnd}), where attoparser reads on. */
  private static final List<String> TAG_ERRORS = List.of("malformed markup: quote ", "malformed markup: U+",
      "malformed markup: '/' before '>'", "malformed markup: a name after '/'");

  /** Building blocks for mutations and random documents: everything the tokenizer treats specially. */
  private static final String[] TOKENS = {
    "<", ">", "</", "/>", "/", "=", "\"", "'", " ", "  ", "\t", "\n", "\r\n", "\r", "\f", "\u000B", "\u001C",
    " ", " ", "😀", "a", "B", "x=y", "-", "--", "[", "]", "?", "!", "&", "&amp;", "&#106;",
    "&#x6A", "&lt;", "&nbsp;", "&bogus;", "t:text=\"${x}\"", "xmlns:t=\"https://tessera.dev\"", "t:if=${a}",
    "<div>", "</div>", "<DIV class=c>", "</Div >", "<div\n  id='i'\n>", "<p>", "</p>", "<li>", "<ul>", "</ul>",
    "<br>", "<br/>", "</br>", "<BR />", "<br/ >", "<img src=a/>", "<input disabled>", "<hr\tnoshade>",
    "<frame>", "</frame>", "<command>", "<menuitem>", "<keygen>", "<wbr>", "<col>",
    "<script>", "</script>", "<SCRIPT>", "</SCRIPT>", "<Script>", "</sCrIpT>", "</script >", "</script/>",
    "</script x>", "</scr", "ipt>", "<script type=\"text/html\">", "<script type=\"application/json\">",
    "<script type=\"\">", "<script type>", "<script type='module'>", "<script type=TEXT/JAVASCRIPT>",
    "<script type=\"text/javascript \">", "<script type=javascript>", "<script t:type=\"x\">",
    "<script type=a type=module>", "<script/>", "<script src=x />", "var s = '</script>';",
    "<style>", "</style>", "<STYLE>", "<style/>", "<textarea>", "</textarea>", "</TEXTAREA>", "</textarea >",
    "<textarea/>", "<title>", "</title>", "<Title>", "</TITLE x>",
    "<!--", "-->", "<!---->", "<!--->", "<!-- c -->", "<!-- a -- b -->", "<![CDATA[", "]]>", "<![CDATA[x]]>",
    "<![cdata[<a>]]>", "<!DOCTYPE html>", "<!doctype html>", "<!DOCTYPE>", "<!DOCTYPE\nhtml>",
    "<!DOCTYPE html PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">",
    "<!DOCTYPE html SYSTEM \"about:legacy-compat\">", "<!DOCTYPE html SYSTEM \"a\" \"b\">", "<!DOCTYPE html PUBLIC>",
    "<!DOCTYPE html FOO \"a\">", "<!DOCTYPE html PUBLIC a>", "<!DOCTYPE html PUBLIC 'a' b>",
    "<!DOCTYPE html [ <!ENTITY x \"y\"> ]>", "<!DOCTYPE html PUBLIC \"a\" \"b\" c>", "<!DOCTYPE html x]>",
    "<!doctypehtml>", "<?xml version=\"1.0\"?>", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>",
    "<?xml?>", "<?xml encoding=\"x\"?>", "<?xml version=1 version=2?>", "<?xml foo=1?>", "<?xml >",
    "<?xml version=1 standalone=yes encoding=x?>", "<?xml =1?>", "<?xml version?>", "<?php echo 1; ?>", "<?a?>",
    "<?ab?>", "<? x ?>", "<!x>", "<! x y>", "<!>", "<>", "</>", "<//>", "<1>", "a < b", "<-x", "<![if]>",
    "<a b c=d e='f' g=\"h\">", "<a b = = c>", "<a =b>", "<a b=\"c\"d=e>", "<a b\"c=d>", "<a href=x\"y>",
    "<a href=/x/>", "<a title=x onclick=y>", "<a A=1 a=2>", "<a b='x\"y'>", "<a b=>", "<a b= >", "<a b=\"\">",
    "<a\u000Bb=c>", "<a<b>", "<a b<c=d>", "<td t:text/>", "[[${x}]]", "[(${x})]", "${x}", "<span t:text=\"${a}\">",
    "</span>", "<ſcript>", "</ſcript>", "<scrİpt>", "<ſtyle>", "<Keygen>", "<svg>", "</svg>",
    "<t:block>", "</t:block>", "<noscript>", "</noscript>", "<XMP>", "</xmp>", "<iframe/>", "<plaintext>",
    "<xmp>", "<NoScript>", "</noscript >", "<iframe>", "</IFRAME>", "<noembed>", "</noembed>", "<noframes>",
    "</noframes>", "<xmp/>",
  };

  /** Single chars and keywords, for character-level soups that reach odd tokenizer states. */
  private static final String[] PIECES = {
    "<", ">", "/", "!", "-", "?", "[", "]", "\"", "'", "=", " ", "\t", "\n", "\r", "\u2003", "\u000B", "&", "a", "b",
    "x", "1", "script", "SCRIPT", "style", "textarea", "title", "br", "img", "frame", "DOCTYPE", "doctype", "CDATA",
    "xml", "PUBLIC", "SYSTEM", "html", "type", "javascript", "module", "version", "encoding", "standalone", "t:text",
  };

  /** Inputs that exercise each rule directly (the token soup below combines them at random). */
  private static final String[] EDGE_CASES = {
    "", "<", "<a", "<a ", "<a b", "<a b=", "<a b='", "<a b='c", "<a b='c'", "</", "</a", "</a ", "<!", "<!-", "<!--",
    "<!-- x", "<!-- x --", "<![CDATA[", "<![CDATA[x]", "<![CDATA[x]]", "<!DOCTYPE", "<!DOCTYPE html", "<!DOCTYPE html [",
    "<?", "<?x", "<?xml", "<?xml ", "<?xml version='1'?", "<script>", "<script>x</script", "<style>a</STYLE>",
    "<title>a</title", "<p>a\r\nb</p>", "<p>\ta b</p>", "😀<p>x</p>", "<a href=\"x\"\r\n  b='y'>z</a>",
    "<textarea>a <b c=\"x></textarea>", "<script type=\"application/json\">{\"a\":\"<b\"}</script>",
    "<script type=\"\">if(a<b){}</script>", "<script type=\"text/javascript \">if(a<b){}</script>",
    "<script>a</script >b</script>", "<script>a</SCRIPT x>b</script>", "<textarea>a</TEXTAREA x>b</textarea>",
    "<title>a <b c=\"</TITLE>\">b</title>", "<title><!-- </title> --></title>", "<script><!-- x --></script>",
    "<div><p>x</div>", "<div></p></div>", "<br></br>", "<p><br/></p>", "<frame><command>",
    "<div a=1 a=2 A=3>", "<div\n a=1\n a=2>", "<a b=\"x\"=y>", "<a =x>", "</a b>", "</a\n>", "</>", "<>",
    "<!---->", "<!--->-->", "<!-- -- -->", "<![CDATA[]]>", "<?ab?>", "<?a?>?>", "<!doctype html><html></html>",
    "<!-- " + "x".repeat(200), "<div class=\"" + "y".repeat(300), "<?xml version=\"1.0\" encoding=\"" + "z".repeat(100) + "\"",
  };

  @Test
  void tokenizerMatchesAttoparser() throws IOException {
    List<String> corpus = corpus();
    assertTrue(corpus.size() > 1000, "corpus has only " + corpus.size() + " inputs; is the extraction broken?");

    Random random = new Random(SEED);
    Set<String> inputs = new LinkedHashSet<>(corpus);
    for (String s : corpus) {
      inputs.addAll(mutations(s, random));
    }
    for (int i = 0; i < 50_000; i++) {
      inputs.add(soup(random, TOKENS, 1 + random.nextInt(i % 10 == 0 ? 80 : 12)));
      inputs.add(soup(random, PIECES, 1 + random.nextInt(40)));
    }
    for (int i = 0; i < 20; i++) {
      // very long inputs: whole corpus entries back to back, then cut
      StringBuilder sb = new StringBuilder();
      while (sb.length() < 50_000) {
        sb.append(corpus.get(random.nextInt(corpus.size())));
      }
      inputs.add(sb.toString());
      inputs.add(sb.substring(0, random.nextInt(sb.length())));
    }

    List<String> failures = new ArrayList<>();
    int compared = 0;
    for (String input : inputs) {
      compared++;
      String mismatch = compare(input);
      if (mismatch != null && failures.size() < 10) {
        failures.add(mismatch);
      } else if (mismatch != null) {
        failures.add("...");
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " of " + compared + " inputs parse differently:\n\n"
          + String.join("\n\n", failures.subList(0, Math.min(11, failures.size()))));
    }
    assertTrue(compared > 100_000, "compared only " + compared + " inputs");
  }

  /** Spot checks that the comparison sees what it should: positions, contexts and diagnostics. */
  @Test
  void comparisonCoversPositionsAndDiagnostics() {
    assertEquals(null, compare("<div a='1'>\n\t<script>[[${x}]]</script></p>"));
    Diagnostics d = new Diagnostics();
    Document doc = new HtmlParser().parse(source("<a\tb=c>"), d);
    assertEquals("Document[source=TemplateSource[viewName=t, path=t.html, content=<a\tb=c>], children=[Element[name=a,"
        + " attributes=[Attribute[name=b, value=c, rawValue=c, quote=\u0000, pos=t.html:1:4, valuePos=t.html:1:6]],"
        + " children=[], selfClosing=false, hasEndTag=false, pos=t.html:1:1]]]", doc.toString());
    assertEquals(List.of("t.html:1:1: error: element <a> is not closed: add </a>"),
        d.all().stream().map(Diagnostic::toString).toList());
  }

  private static TemplateSource source(String content) {
    return new TemplateSource("t", "t.html", content);
  }

  /** Null if both parsers agree on {@code input}, else a description of the difference. */
  private static String compare(String input) {
    Diagnostics expectedDiagnostics = new Diagnostics();
    Document expected = new AttoparserHtmlParser().parse(source(input), expectedDiagnostics);
    Diagnostics actualDiagnostics = new Diagnostics();
    Document actual;
    try {
      actual = new HtmlParser().parse(source(input), actualDiagnostics);
    } catch (RuntimeException e) {
      return "input: " + escape(input) + "\nthrew: " + e;
    }
    List<String> want = expectedDiagnostics.all().stream().map(Diagnostic::toString).toList();
    List<String> got = actualDiagnostics.all().stream().map(Diagnostic::toString).toList();
    if (expected.equals(actual) && want.equals(got)) {
      return null;
    }
    List<Diagnostic> all = actualDiagnostics.all();
    for (int at = 0; at < all.size(); at++) {
      Diagnostic tagError = all.get(at);
      if (TAG_ERRORS.stream().anyMatch(tagError.message()::startsWith)) {
        SourcePos pos = tagError.pos();
        int error = source(input).lineStarts()[pos.line() - 1] + pos.column() - 1;
        // a z after a '/' in a tag starts a name, an error of its own; a '/' at the start of a tag
        // name stops the tag starting. One of the two masks keeps the tag open to the end.
        String z = compareBeforeTagError(input, error, "z", actual, got, at);
        return z == null || compareBeforeTagError(input, error, "/", actual, got, at) == null ? null : z;
      }
    }
    return "input: " + escape(input)
        + "\nattoparser: " + escape(want + " " + expected.children())
        + "\ntessera:    " + escape(got + " " + actual.children());
  }

  /**
   * Compares what comes before a tag error of Tessera's own (see {@link #TAG_ERRORS}: attoparser
   * reads on where a browser and Tessera do not). The tokenizer stops inside the tag that holds the
   * error, so with the offending char and everything after it replaced by {@code mask} (same
   * length, so the length-dependent rules for what starts a tag do not change) Tessera must read
   * the input the same up to that tag, which then has no end ("Incomplete structure" in place of
   * the tag error {@code got[at]}; diagnostics reported after it stay), and attoparser must agree
   * on the replaced input.
   */
  private static String compareBeforeTagError(String input, int error, String mask, Document actual,
      List<String> got, int at) {
    String masked = input.substring(0, error) + mask.repeat(input.length() - error);
    Diagnostics d = new Diagnostics();
    Document doc = new HtmlParser().parse(source(masked), d);
    List<String> maskedGot = d.all().stream().map(Diagnostic::toString).toList();
    List<String> others = new ArrayList<>(got);
    others.remove(at);
    List<String> maskedOthers = new ArrayList<>(maskedGot);
    String incomplete = maskedOthers.size() == got.size() ? maskedOthers.remove(at) : "";
    if (!doc.children().equals(actual.children()) || !maskedOthers.equals(others)
        || !incomplete.contains("error: malformed markup: Incomplete structure")) {
      return "input: " + escape(input) + "\nTessera reads the input up to its tag error differently from: "
          + escape(masked) + "\nmasked: " + escape(maskedGot + " " + doc.children())
          + "\nfull:   " + escape(got + " " + actual.children());
    }
    return compare(masked);
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : (s.length() > 2000 ? s.substring(0, 2000) + "..." : s).toCharArray()) {
      if (c == '\n') {
        sb.append("\\n");
      } else if (c < 0x20 || c > 0x7E) {
        sb.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // ---- generated inputs ----

  private static List<String> mutations(String s, Random random) {
    List<String> out = new ArrayList<>();
    if (s.isEmpty()) {
      return out;
    }
    List<Integer> special = new ArrayList<>();
    for (int i = 0; i < s.length(); i++) {
      if ("<>\"'=/-![]?&\n".indexOf(s.charAt(i)) >= 0) {
        special.add(i);
      }
    }
    if (special.isEmpty()) {
      special.add(0);
    }
    // EOF in every state: cut at (or right after) every special char of short inputs, some of long ones
    int cuts = s.length() <= 400 ? special.size() : 12;
    for (int k = 0; k < cuts; k++) {
      int at = s.length() <= 400 ? special.get(k) : special.get(random.nextInt(special.size()));
      out.add(s.substring(0, at));
      out.add(s.substring(0, Math.min(s.length(), at + 1)));
    }
    for (int k = 0; k < 6; k++) {
      int at = special.get(random.nextInt(special.size()));
      int other = random.nextInt(s.length() + 1);
      String token = TOKENS[random.nextInt(TOKENS.length)];
      switch (k) {
        case 0 -> out.add(s.substring(0, at) + s.substring(Math.min(s.length(), at + 1)));
        case 1 -> out.add(s.substring(0, at) + s.charAt(Math.min(at, s.length() - 1)) + s.substring(at));
        case 2 -> out.add(s.substring(0, at) + token + s.substring(at));
        case 3 -> out.add(s.substring(0, other) + token + s.substring(other));
        case 4 -> out.add(s.substring(0, at) + " \t\r\n \u000B😀".charAt(random.nextInt(8)) + s.substring(at));
        default -> out.add(flipTagCase(s, random));
      }
    }
    if (s.length() < 2000) {
      out.add("<div xmlns:t=\"https://tessera.dev\">" + s + "</div>");
      out.add("<textarea>" + s + "</textarea>");
      out.add("<script>" + s + "</script>");
    }
    return out;
  }

  private static final Pattern TAG_NAME = Pattern.compile("</?([A-Za-z][A-Za-z0-9:-]*)");

  private static String flipTagCase(String s, Random random) {
    Matcher m = TAG_NAME.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String flipped = switch (random.nextInt(4)) {
        case 0 -> name.toUpperCase(Locale.ROOT);
        case 1 -> name.toLowerCase(Locale.ROOT);
        case 2 -> name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        default -> name;
      };
      m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replace(name, flipped)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String soup(Random random, String[] from, int tokens) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens; i++) {
      sb.append(from[random.nextInt(from.length)]);
    }
    return sb.toString();
  }

  // ---- corpus ----

  private static List<String> corpus() throws IOException {
    Set<String> out = new LinkedHashSet<>(List.of(EDGE_CASES));
    List<Path> java = new ArrayList<>();
    try (Stream<Path> files = Files.walk(ROOT)) {
      for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
        String rel = ROOT.relativize(p).toString().replace('\\', '/');
        if (rel.startsWith("bench/") || rel.startsWith("target/") || rel.contains("/target/") || rel.startsWith(".")) {
          continue;
        }
        if (rel.endsWith(".html")) {
          out.add(Files.readString(p, StandardCharsets.UTF_8));
        } else if (rel.endsWith(".md")) {
          out.addAll(fencedBlocks(Files.readString(p, StandardCharsets.UTF_8)));
        } else if (rel.endsWith(".java") && rel.contains("/src/test/java/")) {
          java.add(p);
        }
      }
    }
    out.addAll(stringLiterals(java));
    return new ArrayList<>(out);
  }

  private static final Pattern FENCE = Pattern.compile("(?ms)^\\s*```[\\w-]*\\n(.*?)^\\s*```");

  private static List<String> fencedBlocks(String markdown) {
    List<String> out = new ArrayList<>();
    Matcher m = FENCE.matcher(markdown);
    while (m.find()) {
      if (m.group(1).indexOf('<') >= 0) {
        out.add(m.group(1));
      }
    }
    return out;
  }

  /** String literals (and constant concatenations of them) containing {@code <}, parsed by javac. */
  private static List<String> stringLiterals(List<Path> files) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    List<String> out = new ArrayList<>();
    try (StandardJavaFileManager fm = javac.getStandardFileManager(d -> { }, Locale.ROOT, StandardCharsets.UTF_8)) {
      JavacTask task = (JavacTask) javac.getTask(null, fm, d -> { }, List.of("-proc:none"), null,
          fm.getJavaFileObjectsFromPaths(files));
      for (CompilationUnitTree unit : task.parse()) {
        unit.accept(new TreeScanner<Void, Void>() {
          @Override
          public Void visitLiteral(LiteralTree node, Void unused) {
            add(node);
            return null;
          }

          @Override
          public Void visitBinary(BinaryTree node, Void unused) {
            add(node);
            return super.visitBinary(node, unused);
          }

          private void add(ExpressionTree node) {
            String value = constant(node);
            if (value != null && value.indexOf('<') >= 0) {
              out.add(value);
            }
          }
        }, null);
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    return out;
  }

  private static String constant(ExpressionTree node) {
    if (node instanceof LiteralTree literal) {
      Object value = literal.getValue();
      return value instanceof String || value instanceof Character ? String.valueOf(value) : null;
    }
    if (node instanceof BinaryTree binary && node.getKind() == Tree.Kind.PLUS) {
      String left = constant(binary.getLeftOperand());
      String right = constant(binary.getRightOperand());
      return left == null || right == null ? null : left + right;
    }
    return null;
  }
}
