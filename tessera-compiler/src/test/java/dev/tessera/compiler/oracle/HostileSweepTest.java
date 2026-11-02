package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.RegExpLiteral;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.UnaryExpression;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * The hostile-value sweep (#543): finds XSS mechanically, for the value families the build-time
 * verifier does not cover (security-architecture.md section 11.5: C, D, E and CSS). Three passes, each a
 * gate in {@code mvn verify}; each writes its report to {@code target/hostile-sweep/<pass>.txt}.
 *
 * <ul>
 *   <li><b>Attributes.</b> Every name in {@code attributes.txt}, plus names outside HTML and odd
 *       spellings, on representative HTML elements, as {@code t:<name>="${s}"}; the
 *       names outside HTML also with each framework-neutral shape (a link with a path variable, a
 *       number, a boolean). What the compiler accepts is rendered with a hostile corpus, parsed with
 *       validator.nu, and each attribute of the target element is classified by {@link HostileSinks},
 *       a sink table written from the specs: for a name outside HTML, the value must be in the
 *       neutral grammar (#583). A ratchet: each (element, attribute, sink) hit must have a justified
 *       line in {@code oracle/hostile-sweep-baseline.txt}, and a line whose hit no longer occurs must
 *       be deleted.
 *   <li><b>Sanitizer.</b> {@code html.sanitize} ({@link HtmlPolicy}) over every policy element and
 *       attribute, and some it never allows, with the same values, checked against the same sink
 *       table. Same baseline, elements spelled {@code sanitize:<policy>:<element>}.
 *   <li><b>Scripts.</b> {@code <script>PRE[[${x}]]POST</script>} over the script prelude grammar
 *       (#540) and the expression positions it rejects, for each Java value type. For every accepted
 *       shape, the reference {@code PRE("m")POST} and each hostile render are parsed with Rhino: the
 *       value must be one literal spanning exactly the hole, and the AST outside it must equal the
 *       reference. No baseline: any hit fails. This is the one Rhino check of script values; the
 *       oracle gate has none of its own (#591).
 * </ul>
 *
 * <p>The surefire {@code release-8} execution excludes {@code oracle/**}, so the sweep runs once.
 */
class HostileSweepTest {

  static final int BATCH = 300;
  static final ForkJoinPool POOL =
      new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
  static final String BASELINE = "oracle/hostile-sweep-baseline.txt";

  // =====================================================================================
  // Attribute sweep
  // =====================================================================================

  record Variant(String id, String open, String tag, String extra, boolean isVoid, String close) {}

  static final List<Variant> VARIANTS = new ArrayList<>();

  static void html(String id, String open, String tag, String extra, boolean isVoid, String close) {
    VARIANTS.add(new Variant(id, open, tag, extra, isVoid, close));
  }

  static {
    for (String t : List.of("a", "img", "iframe", "form", "object", "embed", "video", "base", "link", "meta",
        "blockquote", "q", "div", "template", "textarea", "font", "x-widget", "input", "button", "script")) {
      boolean v = Set.of("img", "embed", "base", "link", "meta", "input").contains(t);
      html(t, "", t, "", v, "");
    }
    html("area", "<map name=\"m\">", "area", "", true, "</map>");
    html("source", "<video>", "source", "", true, "</video>");
    html("track", "<video>", "track", "", true, "</video>");
    html("td", "<table><tbody><tr>", "td", "", false, "</tr></tbody></table>");
    html("option", "<select>", "option", "", false, "</select>");
    html("meta-refresh", "", "meta", "http-equiv=\"refresh\"", true, "");
    html("meta-ctype", "", "meta", "http-equiv=\"Content-Type\"", true, "");
  }

  /**
   * Whether the sweep tries a name from these specs: HTML, obsolete HTML and ARIA names. The
   * variants are HTML elements only: since #678 every computed attribute in SVG or MathML is a
   * build error ({@link #computedAttributesInSvgAndMathMlAreRejected}), so SVG- and MathML-only
   * names would only spend the CPU budget.
   */
  static boolean tries(Set<String> specs) {
    return specs.contains("html") || specs.contains("aria") || specs.contains("html-obsolete");
  }

  /**
   * Names outside HTML that client code commonly reads (#583 rule 3), tried with every value shape
   * on {@link #NEUTRAL_VARIANTS}. The sweep doesn't know what any of them does: it holds each value
   * to the neutral grammar ({@link HostileSinks#neutral}).
   */
  static final List<String> OUTSIDE_HTML = List.of("data-x", "data-hx-get", "hx-get", "hx-vals", "hx-on:click",
      "hx-boost", "x-data", "x-bind:href", "v-html", "v-bind:href", "ng-init", "data-ng-init", "x_ng-bind",
      "data-bind", "data-on-click", "up-href", "_", "script", "params", "foo");
  static final Set<String> NEUTRAL_VARIANTS = Set.of("div", "a", "form", "iframe", "script", "template",
      "textarea", "x-widget");

  /** Names that are not plain or not lower case, tried with a String on every variant. */
  static final List<String> OTHER_NAMES = List.of(":title", "@click", "#default", ".prop", ":href", ":src",
      ":inner-h-t-m-l", "onclick", "ONCLICK", "onbegin", "onload", "FILL", "STYLE", "HREF", "XLINK:HREF", "Src",
      "MASK", "CLASS", "Is", "XMLNS", "Charset");

  static final String BENIGN = "tsweepbenign";

  static final List<String> HOSTILE_ATTR = List.of(
      // URL
      "javascript:alert(1)", " JaVaScRiPt:alert(1)", "java\tscript:alert(1)", "\u0001javascript:alert(1)",
      "vbscript:msgbox(1)", "data:text/html,<script>alert(1)</script>", "//evil.example/x.js",
      "https://evil.example/x.js", "/\\evil.example/x", "\\\\evil.example/x", "/ok javascript:alert(1)",
      "/ok.png 1x, javascript:alert(1) 2x", "/ok.png 1x,data:image/svg+xml,x 2x", "/ok;javascript:alert(1)",
      "0;javascript:alert(1)", "0;url=javascript:alert(1)", "5; url=//evil.example",
      // CSS
      "url(//evil.example/x)", "u\\72l(//evil.example/x)", "red;background:url(//evil.example/x)",
      "image-set('//evil.example/a' 1x)", "expression(alert(1))", "url(#a) url(//evil.example/x)", "red",
      // tree builder / policy
      "text/html", "x-evil", "open", "utf-7", "hidden", "href", "http://www.w3.org/1999/xhtml",
      "allow-scripts allow-same-origin", "stylesheet", "unsafe-url",
      // code that client code may evaluate, and values just outside the neutral grammar (#583)
      "constructor.constructor('alert(1)')()", "{{constructor.constructor('alert(1)')()}}", "js:alert(1)",
      "click[alert(1)]", "/a/-location.port--", "-location.port--", "/a?x=1", "/a#b", "x=alert(1)", "1e3", "a/b",
      "%2D%2D", "-%2D", "..", ".", "",
      // markup and switches
      "<img src=x onerror=alert(1)>", "true");

  /** {@code BigDecimal} values: the exact-class rule and plain decimal (#553, #584). */
  static final List<Object> HOSTILE_DECIMAL = List.of(new BigDecimal("1E+400"), new BigDecimal("-1E-30"),
      new BigDecimal("0.000"), new BigDecimal("-0"), new BigDecimal("123456789012345678901234567890.5"),
      new BigDecimal("1") {
        @Override
        public String toPlainString() {
          return "alert(1)";
        }

        @Override
        public String toString() {
          return "alert(1)";
        }
      });
  /** {@code double} values. */
  static final List<Object> HOSTILE_DOUBLE = List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
      1e300, -0.0, Double.MIN_VALUE, 1e-8, -1.5);

  /**
   * The value shapes of a computed attribute: the template's parameter, its expression, the benign
   * value and the hostile ones. {@code s} is a String on every name; the others are the neutral
   * shapes (#584), tried on {@link #OUTSIDE_HTML}: a link with a String path variable, a
   * {@code BigDecimal}, a {@code double} and a {@code boolean}.
   */
  record Shape(String id, String param, String expr, Object benign, List<?> hostile) {}

  static final Shape STRING = new Shape("s", "String s", "${s}", BENIGN, HOSTILE_ATTR);
  static final List<Shape> NEUTRAL_SHAPES = List.of(
      new Shape("p", "String s", "@{/p/{v}(v=${s})}", BENIGN, HOSTILE_ATTR),
      new Shape("n", "java.math.BigDecimal s", "${s}", new BigDecimal("4242"), HOSTILE_DECIMAL),
      new Shape("f", "double s", "${s}", 4242.5, HOSTILE_DOUBLE),
      new Shape("b", "boolean s", "${s}", false, List.of(true)));

  record AttrCase(String view, Variant variant, String name, Shape shape, String template) {}

  /**
   * One finding: {@code element} is the sweep's element variant, {@code attr} the parsed attribute
   * (lower case), {@code repro} the template or sanitizer input that produced it.
   */
  record Hit(String kind, String element, String attr, String value, String detail, String repro) {
    String key() {
      return element + " " + attr + " " + kind;
    }
  }

  @Test
  void attributeSweep() throws Exception {
    long t0 = System.nanoTime();
    long cpu0 = cpuNanos();
    Map<String, Set<String>> specs = HostileSinks.STANDARD;
    List<String> names = new ArrayList<>(new LinkedHashSet<>(
        concat(new ArrayList<>(specs.keySet()), OUTSIDE_HTML, OTHER_NAMES)));
    List<AttrCase> cases = new ArrayList<>();
    for (Variant v : VARIANTS) {
      for (String n : names) {
        if (specs.containsKey(n) && !tries(specs.get(n))) {
          continue;
        }
        List<Shape> shapes = new ArrayList<>(List.of(STRING));
        if (OUTSIDE_HTML.contains(n) && NEUTRAL_VARIANTS.contains(v.id())) {
          shapes.addAll(NEUTRAL_SHAPES);
        }
        for (Shape sh : shapes) {
          String view = String.format("w%06d", cases.size());
          String el = "<" + v.tag() + " data-tsweep=\"\" " + (v.extra().isEmpty() ? "" : v.extra() + " ") + "t:" + n
              + "=\"" + sh.expr() + "\">" + (v.isVoid() ? "" : "</" + v.tag() + ">");
          cases.add(new AttrCase(view, v, n, sh,
              "<div t:params=\"" + sh.param() + "\">" + v.open() + el + v.close() + "</div>"));
        }
      }
    }
    Map<String, Compilation> accepted = compileAll(cases.stream().collect(
        Collectors.toMap(AttrCase::view, AttrCase::template, (a, b) -> a, LinkedHashMap::new)));
    long t1 = System.nanoTime();
    long cpu1 = cpuNanos();

    Map<String, Hit> hits = new ConcurrentHashMap<>();
    AtomicLong renders = new AtomicLong();
    AtomicLong threw = new AtomicLong();
    List<AttrCase> ok = cases.stream().filter(c -> accepted.containsKey(c.view())).toList();
    POOL.submit(() -> ok.parallelStream().forEach(c -> {
      TemplateEntry entry = accepted.get(c.view()).registry().find(c.view()).orElseThrow();
      Document bd = SpecParser.parse(wrap(render(entry, Map.of("s", c.shape().benign()))), true);
      String bShape = shape(bd);
      Element bt = target(bd);
      Set<String> bAttrs = attrNames(bt);
      String tName = c.name().toLowerCase(Locale.ROOT);
      for (Object hv : c.shape().hostile()) {
        String h = String.valueOf(hv);
        String out;
        try {
          out = render(entry, Map.of("s", hv));
        } catch (RuntimeException e) {
          threw.incrementAndGet(); // the render fails, so nothing reaches the page
          continue;
        }
        renders.incrementAndGet();
        Document d = SpecParser.parse(wrap(out), true);
        Element t = target(d);
        if (t == null || !shape(d).equals(bShape)) {
          put(hits, new Hit("STRUCTURE", c.variant().id(), tName, h, out, c.template()));
          continue;
        }
        if (!attrNames(t).equals(bAttrs)) {
          put(hits, new Hit("ATTR_SET_CHANGED", c.variant().id(), tName, h, attrNames(t).toString(), c.template()));
        }
        NamedNodeMap as = t.getAttributes();
        for (int i = 0; i < as.getLength(); i++) {
          Attr a = (Attr) as.item(i);
          if (a.getName().equals("data-tsweep")
              || (bt.hasAttribute(a.getName()) && !bt.getAttribute(a.getName()).contains(BENIGN)
                  && bt.getAttribute(a.getName()).equals(a.getValue()))) {
            continue; // a static attribute the value did not reach
          }
          String name = attrName(a);
          for (String[] f : HostileSinks.classify(t, name, a.getValue(), h)) {
            put(hits, new Hit(f[0], c.variant().id(), name.toLowerCase(Locale.ROOT), h,
                name + "=" + a.getValue() + " :: " + f[1], c.template()));
          }
        }
      }
    })).get();
    String stats = String.format("cases=%d accepted=%d renders=%d threw=%d compile=%.1fs render+check=%.1fs cpu=%.1fs+%.1fs",
        cases.size(), accepted.size(), renders.get(), threw.get(), (t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9,
        (cpu1 - cpu0) / 1e9, (cpuNanos() - cpu1) / 1e9);
    StringBuilder accepts = new StringBuilder("# accepted (element t:name shape)\n");
    ok.forEach(c -> accepts.append(c.variant().id()).append(' ').append(c.name()).append(' ').append(c.shape().id())
        .append('\n'));
    gate("attr", stats, hits, accepts.toString());
  }

  // =====================================================================================
  // Sanitizer sweep
  // =====================================================================================

  static final Map<String, HtmlPolicy> POLICIES = new LinkedHashMap<>();

  static {
    POLICIES.put("BASIC", HtmlPolicy.BASIC);
    POLICIES.put("RICH", HtmlPolicy.RICH);
    // Everything a policy can allow: every element, attribute and class token, link target and rel.
    POLICIES.put("wide", HtmlPolicy.builder()
        .allowElements(HtmlPolicy.ELEMENTS.toArray(new String[0]))
        .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
        .allowClasses(SanitizerBalanceTest.EVERY_CLASS)
        .allowLinkTarget()
        .linkRel(HtmlPolicy.REL_TOKENS.toArray(new String[0]))
        .build());
  }

  /** Attributes the sanitizer sweep writes: every policy attribute, the checked ones, and some never allowed. */
  static final List<String> SANITIZER_ATTRIBUTES = concat(new ArrayList<>(new TreeSet<>(HtmlPolicy.ATTRIBUTES)),
      List.of("href", "src", "class", "rel", "target", "style", "srcset", "background", "action", "formaction",
          "xlink:href", "onclick", "onerror", "ng-init", "data-ng-init", "x_ng-bind", "v-html", ":href", "x-html",
          "hx-get", "hx-on:click", "is", "xmlns", "data-x"));

  @Test
  void sanitizerSweep() throws Exception {
    long t0 = System.nanoTime();
    List<String> elements = concat(new ArrayList<>(new TreeSet<>(HtmlPolicy.ELEMENTS)),
        List.of("script", "style", "iframe", "form", "object", "svg", "math", "x-widget", "template"));
    Map<String, Hit> hits = new ConcurrentHashMap<>();
    AtomicLong inputs = new AtomicLong();
    List<String[]> work = new ArrayList<>();
    for (String p : POLICIES.keySet()) {
      for (String e : elements) {
        work.add(new String[] {p, e});
      }
    }
    POOL.submit(() -> work.parallelStream().forEach(w -> {
      HtmlPolicy policy = POLICIES.get(w[0]);
      String e = w[1];
      for (String a : SANITIZER_ATTRIBUTES) {
        for (String h : HOSTILE_ATTR) {
          String input = "<" + e + " " + a + "=\"" + h.replace("&", "&amp;").replace("\"", "&quot;") + "\">x</" + e + ">";
          inputs.incrementAndGet();
          String out = SafeHtml.sanitize(input, policy).html();
          for (Hit hit : sanitizerHits("sanitize:" + w[0] + ":" + e, policy, out, h, input)) {
            put(hits, hit);
          }
        }
      }
    })).get();
    String stats = String.format("policies=%d inputs=%d time=%.1fs", POLICIES.size(), inputs.get(),
        (System.nanoTime() - t0) / 1e9);
    gate("sanitize", stats, hits, "");
  }

  /** The sink table over every element of a sanitizer output, plus any element no policy allows. */
  static List<Hit> sanitizerHits(String element, HtmlPolicy policy, String out, String h, String input) {
    List<Hit> found = new ArrayList<>();
    Document d = SpecParser.parse(wrap(out), true);
    NodeList all = ((Element) d.getElementsByTagName("body").item(0)).getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element el = (Element) all.item(i);
      if (!"http://www.w3.org/1999/xhtml".equals(el.getNamespaceURI())
          || !HtmlPolicy.ELEMENTS.contains(el.getLocalName())) {
        found.add(new Hit("SANITIZER_ELEMENT", element, el.getLocalName(), h, out, input));
        continue;
      }
      NamedNodeMap as = el.getAttributes();
      for (int j = 0; j < as.getLength(); j++) {
        Attr a = (Attr) as.item(j);
        String name = attrName(a).toLowerCase(Locale.ROOT);
        for (String[] f : HostileSinks.classify(el, attrName(a), a.getValue(), h)) {
          found.add(new Hit(f[0], element, name, h, el.getLocalName() + " " + name + "=" + a.getValue() + " :: "
              + f[1], input));
        }
      }
    }
    return found;
  }

  // =====================================================================================
  // Script sweep
  // =====================================================================================

  /** Prelude declarations (#540): keyword, gap, name, '=' spacing, and the text before and after. */
  static final List<String> KEYWORDS = List.of("const", "let", "var");
  static final List<String> GAPS = List.of(" ", "\t", "  ");
  static final List<String> NAMES = List.of("a", "$a", "_a", "a1", "location", "let", "yield", "await");
  static final List<String> EQUALS = List.of(" = ", "=", " =\t", "\n=\n");
  static final List<String> PRELUDE_BEFORE = List.of("", "\n", "  ", "// c\n", "\n\t// c\n", "var b = 1;\n",
      "/* c */ ", "<!-- c\n", "x\n");
  static final List<String> PRELUDE_AFTER = List.of(";", ";\n", "; // c", ";\nf(a);", ";\n// c\nvar b = 1;",
      ";\na.toString();", ";\n[0]", ";\n(1)", ";\n`x`", ";\n/x/g", ";\n--> x", "", "\n[0]", "\n(1)", "\n`x`",
      ".x;", "[0];", "(1);", " + 1;", "\n.x;", ", b = 1;");
  static final List<String> SCRIPT_TYPES = List.of("", " type=\"module\"", " type=\"text/javascript\"");

  /** Expression positions outside the prelude, all rejected since #540; an accepted one is checked the same way. */
  static final List<String[]> CONTEXTS = List.of(
      new String[] {"var a = ", ";"}, new String[] {"f(", ");"}, new String[] {"", ""},
      new String[] {"function g() { return ", "; }"});
  static final List<String> BEFORE = List.of("", "x", "1", "1.", "globalThis", "o.", "o?.", "this", "x -", "x - ",
      "-", "- ", "+", "!", "~", "1 < !- ", "typeof ", "typeof", "void ", "2 ** ", "x\n", "1\n", "x++", "a, ", "\"\"",
      "x /* c */ ", "`t`", "new ", "x in ", "c ? 1 : ", "-(", "o[", "[", "{k: ", "x = ", "!-");
  static final List<String> AFTER = List.of("", ".toString()", ".toFixed(1)", ".length", "[0]", "(1)", "?.x",
      "?.(1)", "`x`", "e3", "n", "x", ".5", ".e3", "..toString()", " ** 2", "**2", "++", "--", "\n[0]", "\n(1)",
      "\n`x`", ".\nfoo", ": 1", " in o", " instanceof Object", "/2", "/2/g", " = 1", "=>1", ")", "]", "}", " ? 1 : 2",
      "\n-->x", " --> x");
  static final List<String> VARS = List.of("s", "n", "d", "bd", "l");
  /**
   * Value types written by the generated serializers and the boxed paths (#591): a Boolean, a Long,
   * a map, an enum, a record and a bean, in two prelude declarations per script type. The grammar is
   * covered by {@link #VARS}; these reach the escaper paths the oracle gate's script check used to.
   * TODO(#618): the generator's containers around an accepted prelude script.
   */
  static final List<String> SERIALIZED_VARS = List.of("b", "lg", "m", "e", "r", "p");
  static final String SCRIPT_PARAMS = "String s, int n, double d, java.math.BigDecimal bd, java.util.List<String> l,"
      + " Boolean b, Long lg, java.util.Map<String, String> m, com.example.SweepKind e,"
      + " com.example.SweepRecord r, com.example.SweepBean p";
  static final Map<String, String> SWEEP_SOURCES = Map.of(
      "com.example.SweepKind", "package com.example; public enum SweepKind { A, B }",
      "com.example.SweepRecord",
      "package com.example; public record SweepRecord(String s, int n, java.util.List<String> l) {}",
      "com.example.SweepBean", "package com.example; @dev.tessera.runtime.TemplateView public final class SweepBean {"
          + " private final String s;"
          + " public SweepBean(String s) { this.s = s; } public String getS() { return s; } }");

  record ScriptCase(String view, String var, String type, String pre, String post, String template) {}

  static Map<String, List<Object>> scriptValues() {
    Map<String, List<Object>> v = new LinkedHashMap<>();
    v.put("s", List.of("x", "", "</script><script>alert(1)</script>", "\"-alert(1)-\"", "'-alert(1)-'", "\\",
        "\u2028\u2029", "<!--<script>", "-->", "${alert(1)}", "`", "]]>", "use strict", "\ud800", "\")(alert(1)"));
    v.put("n", List.of(0, 5, -1, Integer.MIN_VALUE, Integer.MAX_VALUE));
    v.put("d", List.of(5.0, 0.5, -1.5, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e21,
        1e-7, -1e21));
    BigDecimal evil = new BigDecimal("1") {
      @Override
      public String toString() {
        return "alert(1)";
      }

      @Override
      public String toPlainString() {
        return "alert(1)";
      }

      @Override
      public String toEngineeringString() {
        return "alert(1)";
      }
    };
    v.put("bd", List.of(new BigDecimal("5"), new BigDecimal("-1"), new BigDecimal("1E+3"), new BigDecimal("-0.00"),
        new BigDecimal("1E-10"), evil));
    v.put("l", List.of(List.of(), List.of("a"), List.of("</script>", "x")));
    v.put("b", List.of(true, false));
    v.put("lg", List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE, 9007199254740993L));
    v.put("m", List.of(Map.of(), Map.of("</script><script>alert(1)</script>", "\"-alert(1)-\"", "k", "\u2028`${x}`")));
    return v;
  }

  /** The values of {@link #SWEEP_SOURCES} types, loaded from the case's compilation. */
  static List<Object> compiledValues(String var, Compilation c) {
    return switch (var) {
      case "e" -> List.of(c.enumConstant("com.example.SweepKind", "A"), c.enumConstant("com.example.SweepKind", "B"));
      case "r" -> List.of(c.newInstance("com.example.SweepRecord", "x", 0, List.of()),
          c.newInstance("com.example.SweepRecord", "</script><script>alert(1)</script>", Integer.MIN_VALUE,
              List.of("\"-alert(1)-\"", "<!--<script>")));
      case "p" -> List.of(c.newInstance("com.example.SweepBean", "x"),
          c.newInstance("com.example.SweepBean", "'-alert(1)-'</script>"));
      default -> throw new IllegalArgumentException(var);
    };
  }

  @Test
  void scriptSweep() throws Exception {
    long t0 = System.nanoTime();
    Set<String> shapes = new LinkedHashSet<>();
    List<ScriptCase> cases = new ArrayList<>();
    for (String var : VARS) {
      for (String type : SCRIPT_TYPES) {
        for (String kw : KEYWORDS) {
          for (String gap : GAPS) {
            for (String name : NAMES) {
              for (String eq : EQUALS) {
                add(cases, shapes, var, type, kw + gap + name + eq, ";");
              }
            }
          }
        }
        for (String b : PRELUDE_BEFORE) {
          for (String a : PRELUDE_AFTER) {
            add(cases, shapes, var, type, b + "const a = ", a);
          }
        }
      }
      for (String[] ctx : CONTEXTS) {
        for (String b : BEFORE) {
          add(cases, shapes, var, "", ctx[0] + b, ctx[1]);
        }
        for (String a : AFTER) {
          add(cases, shapes, var, "", ctx[0], a + ctx[1]);
        }
      }
    }
    for (String var : List.of("s", "n", "d")) {
      for (String b : BEFORE) {
        for (String a : AFTER) {
          add(cases, shapes, var, "", "var a = " + b, a + ";");
        }
      }
    }
    for (String var : SERIALIZED_VARS) {
      for (String type : SCRIPT_TYPES) {
        add(cases, shapes, var, type, "const a = ", ";");
        add(cases, shapes, var, type, "\nvar a=", ";\nf(a);");
      }
    }
    Map<String, Compilation> accepted = compileAll(cases.stream().collect(
        Collectors.toMap(ScriptCase::view, ScriptCase::template, (x, y) -> x, LinkedHashMap::new)));
    long t1 = System.nanoTime();
    Map<String, List<Object>> values = scriptValues();
    Map<String, Hit> hits = new ConcurrentHashMap<>();
    AtomicLong renders = new AtomicLong();
    List<ScriptCase> ok = cases.stream().filter(c -> accepted.containsKey(c.view())).toList();
    POOL.submit(() -> ok.parallelStream().forEach(c -> {
      String shapeId = c.var() + " |" + c.type() + " " + c.pre() + "[[" + c.var() + "]]" + c.post();
      String ref = c.pre() + "(\"tqm0001\")" + c.post();
      Parsed r = parse(ref, !c.type().isEmpty() && c.type().contains("module"));
      if (r.root == null) {
        put(hits, new Hit("SHAPE_NO_LITERAL_FITS", esc(shapeId), c.var(), "", r.error, c.template()));
        return;
      }
      AstNode rSlot = span(r.root, c.pre().length(), c.pre().length() + "(\"tqm0001\")".length());
      if (rSlot == null) {
        put(hits, new Hit("SHAPE_NOT_EXPRESSION", esc(shapeId), c.var(), "", "no operand spans the hole in "
            + esc(ref), c.template()));
        return;
      }
      String rTree = tree(r.root, rSlot);
      Compilation comp = accepted.get(c.view());
      TemplateEntry entry = comp.registry().find(c.view()).orElseThrow();
      Map<String, Object> defaults = new HashMap<>(DEFAULTS);
      for (String x : List.of("e", "r", "p")) {
        defaults.put(x, compiledValues(x, comp).get(0));
      }
      for (Object v : values.containsKey(c.var()) ? values.get(c.var()) : compiledValues(c.var(), comp)) {
        Map<String, Object> model = new HashMap<>(defaults);
        model.put(c.var(), v);
        String out;
        try {
          out = render(entry, model);
        } catch (RuntimeException e) {
          continue; // refused (a BigDecimal subclass, #540): nothing reaches the page
        }
        renders.incrementAndGet();
        String vs = v instanceof BigDecimal && v.getClass() != BigDecimal.class
            ? "BigDecimal{toString=alert(1)}" : String.valueOf(v);
        String problem = checkScript(out, c, v, rTree);
        if (problem != null) {
          int sp = problem.indexOf(' ');
          put(hits, new Hit(problem.substring(0, sp), esc(shapeId), c.var(), vs, problem.substring(sp + 1),
              c.template()));
        }
      }
    })).get();
    String stats = String.format("shapes=%d accepted=%d renders=%d compile=%.1fs check=%.1fs", cases.size(),
        accepted.size(), renders.get(), (t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9);
    StringBuilder accepts = new StringBuilder("# accepted shapes\n");
    ok.forEach(c -> accepts.append(esc(c.var() + " |" + c.type() + " " + c.pre() + "[[" + c.var() + "]]" + c.post()))
        .append('\n'));
    // Every accepted shape is a prelude declaration; a sweep that accepts none checks nothing.
    assertTrue(ok.size() >= VARS.size(), "the script sweep accepted only " + ok.size() + " shapes");
    gate("script", stats, hits, accepts.toString());
  }

  /** Null if the render keeps one literal in the hole, else {@code "KIND detail"}. */
  static String checkScript(String out, ScriptCase c, Object v, String rTree) {
    Document d = SpecParser.parse(wrap(out), true);
    NodeList scripts = d.getElementsByTagName("script");
    if (scripts.getLength() != 1) {
      return "SCRIPT_ELEMENT_CHANGED " + esc(out);
    }
    String text = scripts.item(0).getTextContent();
    if (!text.startsWith(c.pre()) || !text.endsWith(c.post()) || text.length() < c.pre().length() + c.post().length()) {
      return "SCRIPT_STATIC_CHANGED " + esc(text);
    }
    int from = c.pre().length();
    int to = text.length() - c.post().length();
    Parsed h = parse(text, c.type().contains("module"));
    if (h.root == null) {
      return "JS_VALUE_BREAKS_SYNTAX " + esc(text) + " :: " + h.error;
    }
    AstNode slot = span(h.root, from, to);
    if (slot == null) {
      return "JS_VALUE_NOT_ONE_EXPRESSION " + esc(text);
    }
    // A non-finite double is JSON null, as JSON.stringify writes it; never the globals NaN or Infinity,
    // which a page can shadow.
    boolean nonFinite = v instanceof Double && !Double.isFinite((Double) v);
    if (nonFinite ? !isNull(slot) : !literal(slot)) {
      return "JS_VALUE_NOT_LITERAL " + esc(text) + " :: " + slot.getClass().getSimpleName();
    }
    if (!tree(h.root, slot).equals(rTree)) {
      return "JS_STRUCTURE_CHANGED " + esc(text);
    }
    return null;
  }

  static final Map<String, Object> DEFAULTS =
      Map.of("s", "x", "n", 0, "d", 0.0, "bd", BigDecimal.ONE, "l", List.of(), "b", false, "lg", 0L, "m", Map.of());

  static void add(List<ScriptCase> cases, Set<String> seen, String var, String type, String pre, String post) {
    if (!seen.add(var + "\u0000" + type + "\u0000" + pre + "\u0000" + post)) {
      return;
    }
    String view = String.format("j%06d", cases.size());
    cases.add(new ScriptCase(view, var, type, pre, post, "<div t:params=\"" + SCRIPT_PARAMS + "\"><script" + type + ">"
        + pre + "[[${" + var + "}]]" + post + "</script></div>"));
  }

  record Parsed(AstRoot root, String error) {}

  static CompilerEnvirons env() {
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(Context.VERSION_ECMASCRIPT);
    env.setRecordingComments(false);
    env.setXmlAvailable(false);
    env.setRecoverFromErrors(false);
    env.setIdeMode(false);
    env.setErrorReporter(new ErrorReporter() {
      @Override
      public void warning(String m, String s, int l, String ls, int o) {}

      @Override
      public void error(String m, String s, int l, String ls, int o) {
        throw new EvaluatorException(m, s, l, ls, o);
      }

      @Override
      public EvaluatorException runtimeError(String m, String s, int l, String ls, int o) {
        return new EvaluatorException(m, s, l, ls, o);
      }
    });
    return env;
  }

  /** Rhino's parse; a module is parsed in strict mode (Rhino has no module goal). */
  static Parsed parse(String js, boolean module) {
    try {
      CompilerEnvirons env = env();
      env.setStrictMode(module);
      return new Parsed(new Parser(env, env.getErrorReporter()).parse(js, "s.js", 1), null);
    } catch (EvaluatorException | StackOverflowError e) {
      return new Parsed(null, e instanceof EvaluatorException ee ? ee.details() + " @" + ee.columnNumber() : "stack");
    }
  }

  /**
   * The deepest node spanning exactly [from, to). Deepest, because Rhino 1.8.1 gives some parents
   * (a prefix UnaryExpression, an ExpressionStatement) the extent of their operand.
   */
  static AstNode span(AstRoot root, int from, int to) {
    AstNode[] best = new AstNode[1];
    root.visit(n -> {
      if (n != root && n.getAbsolutePosition() == from && n.getAbsolutePosition() + n.getLength() == to
          && !(n instanceof org.mozilla.javascript.ast.ExpressionStatement)) {
        best[0] = n;
      }
      return true;
    });
    return best[0];
  }

  static boolean isNull(AstNode n) {
    if (n instanceof ParenthesizedExpression p) {
      return isNull(p.getExpression());
    }
    return n instanceof KeywordLiteral k && k.getType() == Token.NULL;
  }

  static boolean literal(AstNode n) {
    if (n instanceof ParenthesizedExpression p) {
      return literal(p.getExpression());
    }
    if (n instanceof StringLiteral || n instanceof NumberLiteral) {
      return true;
    }
    if (n instanceof KeywordLiteral k) {
      return k.getType() == Token.TRUE || k.getType() == Token.FALSE || k.getType() == Token.NULL;
    }
    if (n instanceof UnaryExpression u) {
      AstNode o = u.getOperand();
      return u.getType() == Token.NEG && (o instanceof NumberLiteral || o instanceof ParenthesizedExpression p && literal(p));
    }
    if (n instanceof ArrayLiteral a) {
      return a.getElements().stream().allMatch(HostileSweepTest::literal);
    }
    if (n instanceof ObjectLiteral o) {
      for (ObjectProperty p : o.getElements()) {
        if (p.isMethod() || !(p.getLeft() instanceof StringLiteral) || !literal(p.getRight())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** Pre-order serialization; the slot subtree is SLOT. */
  static String tree(AstRoot root, AstNode slot) {
    StringBuilder sb = new StringBuilder();
    root.visit(n -> {
      int depth = 0;
      for (AstNode p = n.getParent(); p != null; p = p.getParent()) {
        depth++;
      }
      sb.append(depth).append(' ');
      if (n == slot) {
        sb.append("SLOT\n");
        return false;
      }
      sb.append(n.getClass().getSimpleName()).append(':').append(n.getType());
      if (n instanceof Name x) {
        sb.append(' ').append(x.getIdentifier());
      } else if (n instanceof StringLiteral x) {
        sb.append(' ').append(x.getValue());
      } else if (n instanceof NumberLiteral x) {
        sb.append(' ').append(x.getValue());
      } else if (n instanceof RegExpLiteral x) {
        sb.append(' ').append(x.getValue()).append(x.getFlags());
      }
      sb.append('\n');
      return true;
    });
    return sb.toString();
  }

  // =====================================================================================
  // The checks find what they claim (negative tests of the oracle itself)
  // =====================================================================================

  /** Why the attribute sweep has no SVG or MathML variants: #678 rejects any computed attribute there. */
  @Test
  void computedAttributesInSvgAndMathMlAreRejected() {
    for (String t : List.of("<svg t:title=\"${s}\"></svg>", "<svg><a t:href=\"${s}\"></a></svg>",
        "<math><mi t:title=\"${s}\"></mi></math>")) {
      Compilation c = Compilation.builder().template("t.html", "<div t:params=\"String s\">" + t + "</div>").compile();
      assertFalse(c.success(), t);
      assertTrue(c.errors().stream().anyMatch(m -> m.contains("nothing inside <svg> or <math> is computed")),
          t + ": " + c.errors());
    }
  }

  /** Hand-written outputs of historic bugs: the sink table names each one. */
  @Test
  void sinkTableFlagsKnownBadOutputs() {
    assertKinds("<a data-tsweep href=\" JaVaScRiPt:alert(1)\">", " JaVaScRiPt:alert(1)", "URL_SCRIPT_SCHEME");
    assertKinds("<img data-tsweep srcset=\"/ok.png 1x, javascript:alert(1) 2x\">",
        "/ok.png 1x, javascript:alert(1) 2x", "URL_SCRIPT_SCHEME", "URLS_FROM_ONE_VALUE");
    assertKinds("<script data-tsweep src=\"//evil.example/x.js\"></script>", "//evil.example/x.js",
        "URL_CROSS_ORIGIN_CODE");
    assertKinds("<p data-tsweep style=\"u\\72l(//evil.example/x)\">", "u\\72l(//evil.example/x)", "CSS_FETCH");
    assertKinds("<meta data-tsweep http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
        "0;url=javascript:alert(1)", "URL_REFRESH_OFFSITE", "URL_SCRIPT_SCHEME");
    // Client-framework bugs of the past (#529, #531, #557, #562, ...), now one rule that names no
    // framework: the value is not neutral.
    for (String[] old : List.of(new String[] {"data--ng-init", "alert(1)"},
        new String[] {":href", "'javascript:alert(1)'"}, new String[] {"hx-vals", "js:alert(1)"},
        new String[] {"data-hx-get", "//evil.example/x"}, new String[] {"ws-connect", "javascript:alert(1)"},
        new String[] {"hx-trigger", "click[alert(1)]"}, new String[] {"hx-headers", "javascript:alert(1)"},
        new String[] {"params", "a: alert(1)"}, new String[] {"script", "on click call alert(1)"},
        new String[] {"v-html", "{{constructor.constructor('alert(1)')()}}"})) {
      assertKinds("<x-widget data-tsweep " + old[0] + "=\"" + old[1].replace("'", "&#39;") + "\"></x-widget>",
          old[1], "NON_NEUTRAL_VALUE");
    }
    assertKinds("<iframe data-tsweep srcdoc=\"&lt;img src=x onerror=alert(1)&gt;\"></iframe>",
        "<img src=x onerror=alert(1)>", "MARKUP_ATTRIBUTE");
    assertKinds("<template data-tsweep for=\"x\"></template>", "x", "TREE_BUILDER_INPUT");
    // Benign values reach nothing.
    assertKinds("<a data-tsweep href=\"/ok\" class=\"x y\" title=\"t\">", "/ok");
  }

  /**
   * #583 rule 3, the sweep's invariant for names outside HTML: the rendered value is in the neutral
   * grammar (#584's safety research), whatever client code reads the name. Each probe breaks one
   * condition of the grammar.
   */
  @Test
  void namesOutsideHtmlHoldOnlyNeutralValues() {
    for (String[] bad : List.of(
        new String[] {"data-name", "alert(1)"}, // a free string
        new String[] {"x-data", "/a/-location.port--"}, // -- is a write primitive
        new String[] {"hx-get", "//evil.example/x"}, // another origin
        new String[] {"hx-get", "/a?x=1"}, // a query
        new String[] {"data-url", "/a#b"}, // a fragment
        new String[] {"v-bind:href", "/a=b"},
        new String[] {"data-p", "/a%zz"}, // % not as %XX
        new String[] {"data-p", "a/b"}, // page-relative
        new String[] {"data-n", "1e3"}, // an exponent
        new String[] {"data-n", "+1"},
        new String[] {"data-n", "0x1"},
        new String[] {"data-n", "NaN"},
        new String[] {"data-n", "Infinity"},
        new String[] {"data-n", "01"},
        new String[] {"data-b", "TRUE"},
        new String[] {"ng-init", "x"},
        new String[] {"\u212Aind", "alert(1)"})) { // KELVIN SIGN: not the standard "kind" to a browser
      assertKinds("<x-widget data-tsweep " + bad[0] + "=\"" + bad[1] + "\"></x-widget>", bad[1],
          "NON_NEUTRAL_VALUE");
    }
    // Positive controls: numbers, booleans and same-origin paths in the alphabet, on any name.
    for (String[] good : List.of(new String[] {"data-id", "42"}, new String[] {"data-n", "-1.5"},
        new String[] {"data-n", "0"}, new String[] {"data-open", "true"}, new String[] {"x-show", "false"},
        new String[] {"hx-get", "/items/7"}, new String[] {"data-url", "/a-%2D-b/c.d_e~f"},
        new String[] {"v-html", "/"}, new String[] {"my-prop", "/%E2%80%A8"})) {
      assertKinds("<x-widget data-tsweep " + good[0] + "=\"" + good[1] + "\"></x-widget>", good[1]);
    }
    // Standard names are held to their own sinks, not to the neutral grammar.
    assertKinds("<p data-tsweep title=\"alert(1)\" class=\"x--y\" aria-label=\"a?b\">", "alert(1)");
  }

  static void assertKinds(String html, String hostile, String... kinds) {
    Element t = target(SpecParser.parse(wrap(html), true));
    Set<String> found = new TreeSet<>();
    NamedNodeMap as = t.getAttributes();
    for (int i = 0; i < as.getLength(); i++) {
      String name = attrName(as.item(i));
      if (name.equals("data-tsweep")) {
        continue; // the sweep's marker, static
      }
      for (String[] f : HostileSinks.classify(t, name, as.item(i).getNodeValue(), hostile)) {
        found.add(f[0]);
      }
    }
    assertEquals(new TreeSet<>(List.of(kinds)), found, html);
  }

  /**
   * The sanitizer pass holds a kept name outside HTML to the neutral grammar, and an element no
   * policy allows is a hit. A class token is a standard attribute value: what client code makes of
   * it is outside the guarantee (#583), so it is no hit.
   */
  @Test
  void sanitizerPassFlagsNamesOutsideHtmlAndUnknownElements() {
    String h = "x _ng-init:alert(1)";
    assertEquals(List.of("NON_NEUTRAL_VALUE"), sanitizerHits("sanitize:wide:p", POLICIES.get("wide"),
        "<p data-x=\"" + h + "\">x</p>", h, "").stream().map(Hit::kind).toList());
    assertEquals(List.of(), sanitizerHits("sanitize:wide:p", POLICIES.get("wide"),
        "<p class=\"" + h + "\">x</p>", h, ""));
    assertEquals(List.of("SANITIZER_ELEMENT"), sanitizerHits("sanitize:wide:p", POLICIES.get("wide"),
        "<svg></svg>", h, "").stream().map(Hit::kind).toList());
  }

  /** The script check rejects a value that is not one literal, and accepts JSON null for NaN. */
  @Test
  void scriptCheckFlagsNonLiterals() {
    ScriptCase c = new ScriptCase("j", "d", "", "var a = ", ";", "");
    AstRoot ref = parse("var a = (\"tqm0001\");", false).root;
    String rTree = tree(ref, span(ref, 8, 19));
    assertEquals(null, checkScript("<script>var a = (null);</script>", c, Double.NaN, rTree));
    assertEquals(null, checkScript("<script>var a = (-1.5);</script>", c, -1.5, rTree));
    assertTrue(checkScript("<script>var a = (NaN);</script>", c, Double.NaN, rTree)
        .startsWith("JS_VALUE_NOT_LITERAL"));
    assertTrue(checkScript("<script>var a = (alert(1));</script>", c, 1.0, rTree)
        .startsWith("JS_VALUE_NOT_LITERAL"));
    assertTrue(checkScript("<script>var a = (1);alert(1);</script>", c, 1.0, rTree)
        .startsWith("JS_VALUE_NOT_ONE_EXPRESSION"));
    assertTrue(checkScript("<script>var a = </script><script>;</script>", c, 1.0, rTree)
        .startsWith("SCRIPT_ELEMENT_CHANGED"));
    // From the deleted ScriptInvariantTest (#591): JSON arrays, objects and hostile strings are one
    // literal; a quoted-hole breakout, a getter, a regex, a name, two tokens or broken syntax is not.
    assertEquals(null, checkScript("<script>var a = ({\"x\":[1,-2.5,true,false,null,\"tqm0001()\"],\"y\":{}});"
        + "</script>", c, "x", rTree));
    assertEquals(null, checkScript("<script>var a = (\"*/tqm0001()/*\\u2028`${x}\");</script>", c, "x", rTree));
    for (String bad : List.of("(\"\"+tqm0001()+\"\")", "({get x() { return 1; }})", "(/tqm0001/)", "([tqm0001])",
        "(\"x\") + (\"y\")")) {
      String p = checkScript("<script>var a = " + bad + ";</script>", c, "x", rTree);
      assertTrue(p != null && p.startsWith("JS_VALUE_NOT_LITERAL"), bad + " :: " + p);
    }
    assertTrue(checkScript("<script>var a = (\"\"x\"\");</script>", c, "x", rTree)
        .startsWith("JS_VALUE_BREAKS_SYNTAX"));
    ScriptCase commented = new ScriptCase("j", "s", "", "var a = ", "; // x", "");
    assertTrue(checkScript("<script>var a = (\"x\"); // y</script>", commented, "x", rTree)
        .startsWith("SCRIPT_STATIC_CHANGED"));
  }

  /**
   * #608: the oracle's copy of the standard attribute names and the compiler's agree name for name
   * and source for source; a name added to only one of them fails here.
   */
  @Test
  void standardNamesAreTheOraclesOwnCopy() {
    assertTrue(!HostileSinks.STANDARD.isEmpty());
    assertEquals(HostileSinks.standardNames(HostileSinks.COMPILER_STANDARD), HostileSinks.STANDARD);
  }

  /** The ratchet fails on an unlisted hit and on a listed hit that no longer occurs. */
  @Test
  void ratchetFailsOnNewHitsAndStaleLines() throws IOException {
    Hit listed = new Hit("URL_CROSS_ORIGIN_CODE", "svg:use", "href", "//evil.example/x", "", "");
    Hit fresh = new Hit("URL_SCRIPT_SCHEME", "a", "href", "javascript:alert(1)", "", "<a t:href=\"${s}\">");
    Map<String, String> baseline = Map.of(listed.key(), "why", "svg:image href CSS_FETCH", "why");
    assertEquals(List.of("NEW a href URL_SCRIPT_SCHEME", "STALE svg:image href CSS_FETCH"),
        problems(baseline, List.of(listed, fresh)));
    assertEquals(List.of(), problems(Map.of(listed.key(), "why"), List.of(listed)));
    // The gate itself, against the checked-in baseline: the unlisted hit fails the build.
    Map<String, Hit> hits = new HashMap<>();
    put(hits, listed);
    put(hits, fresh);
    AssertionError e = assertThrows(AssertionError.class, () -> gate("ratchet-selftest", "", hits, ""));
    assertTrue(e.getMessage().contains("NEW a href URL_SCRIPT_SCHEME"), e.getMessage());
  }

  /** A baseline line needs a justification; a malformed one fails the load. */
  @Test
  void baselineLinesNeedAJustification() {
    assertThrows(IllegalStateException.class, () -> parseBaseline("svg:use href URL_CROSS_ORIGIN_CODE\n"));
    assertEquals(Map.of("svg:use href URL_CROSS_ORIGIN_CODE", "why not"),
        parseBaseline("# c\n\nsvg:use href URL_CROSS_ORIGIN_CODE why not\n"));
  }

  // =====================================================================================
  // Shared
  // =====================================================================================

  static void put(Map<String, Hit> hits, Hit h) {
    hits.putIfAbsent(h.key(), h);
  }

  /** The hits the gate reports, sorted. */
  static List<Hit> reported(Collection<Hit> hits) {
    return hits.stream()
        .sorted((x, y) -> x.key().compareTo(y.key())).toList();
  }

  /**
   * The ratchet's decision: {@code "NEW <key>"} for each reported hit the baseline doesn't list,
   * then {@code "STALE <key>"} for each baseline line no hit matches. Empty means pass.
   */
  static List<String> problems(Map<String, String> baseline, Collection<Hit> hits) {
    List<Hit> reported = reported(hits);
    List<String> out = new ArrayList<>();
    reported.stream().filter(h -> !baseline.containsKey(h.key())).forEach(h -> out.add("NEW " + h.key()));
    Set<String> seen = reported.stream().map(Hit::key).collect(Collectors.toSet());
    new TreeSet<>(baseline.keySet()).stream().filter(k -> !seen.contains(k)).forEach(k -> out.add("STALE " + k));
    return out;
  }

  /** The ratchet: hits not in the baseline fail, and so do baseline lines of this pass with no hit. */
  static void gate(String pass, String stats, Map<String, Hit> hits, String accepts) throws IOException {
    boolean sanitizer = pass.equals("sanitize");
    Map<String, String> baseline = new TreeMap<>();
    if (!pass.equals("script")) {
      parseBaseline(readBaseline()).forEach((k, v) -> {
        if (k.startsWith("sanitize:") == sanitizer) {
          baseline.put(k, v);
        }
      });
    }
    List<Hit> reported = reported(hits.values());
    List<String> problems = problems(baseline, hits.values());
    List<Hit> fresh = reported.stream().filter(h -> problems.contains("NEW " + h.key())).toList();
    List<String> stale = problems.stream().filter(p -> p.startsWith("STALE ")).map(p -> p.substring(6)).toList();

    StringBuilder sb = new StringBuilder();
    sb.append(pass).append(": ").append(stats).append('\n');
    sb.append("hits by kind: ").append(new TreeMap<>(hits.values().stream()
        .collect(Collectors.groupingBy(Hit::kind, Collectors.counting())))).append('\n');
    for (Hit h : reported) {
      sb.append(baseline.containsKey(h.key()) ? "baselined " : "NEW       ").append(h.key()).append('\n');
      sb.append("    value:  ").append(esc(h.value())).append('\n');
      sb.append("    found:  ").append(esc(h.detail())).append('\n');
      sb.append("    repro:  ").append(esc(h.repro())).append('\n');
    }
    for (String k : stale) {
      sb.append("STALE     ").append(k).append('\n');
    }
    sb.append('\n').append(accepts);
    Path dir = Path.of("target", "hostile-sweep");
    Files.createDirectories(dir);
    Path f = dir.resolve(pass + ".txt");
    Files.writeString(f, sb.toString());
    System.out.println("[hostile-sweep] " + pass + ": " + stats + " -> " + f.toAbsolutePath());

    if (!fresh.isEmpty() || !stale.isEmpty()) {
      StringBuilder msg = new StringBuilder("Hostile-value sweep (" + pass + "), " + f + ":\n");
      for (Hit h : fresh) {
        msg.append("  NEW ").append(h.key()).append("\n    value ").append(esc(h.value())).append("\n    found ")
            .append(esc(h.detail())).append("\n    repro ").append(esc(h.repro())).append('\n');
      }
      for (String k : stale) {
        msg.append("  STALE ").append(k).append(": the hit no longer occurs, delete its line\n");
      }
      if (!fresh.isEmpty()) {
        msg.append(pass.equals("script")
            ? "A hostile value is not one JavaScript literal: reject the shape in the compiler.\n"
            : "A hostile value reaches a sink in the spec table (HostileSinks). Reject or escape it in the "
                + "compiler or sanitizer; only a false positive gets a justified line in src/test/resources/"
                + BASELINE + ".\n");
      }
      fail(msg.toString());
    }
  }

  static String readBaseline() throws IOException {
    try (InputStream in = HostileSweepTest.class.getClassLoader().getResourceAsStream(BASELINE)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** {@code <element> <attribute> <KIND> <justification>} per line; {@code #} starts a comment line. */
  static Map<String, String> parseBaseline(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    String[] lines = text.split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split("\\s+", 4);
      if (p.length < 4 || p[3].isBlank() || !p[2].matches("[A-Z_]+")) {
        throw new IllegalStateException(BASELINE + ":" + (i + 1)
            + ": want '<element> <attribute> <KIND> <justification>', got: " + line);
      }
      out.put(p[0] + " " + p[1] + " " + p[2], p[3]);
    }
    return out;
  }

  static long cpuNanos() {
    return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
        ? os.getProcessCpuTime() : 0;
  }

  static final Pattern ESCAPED_NAME_CHAR = Pattern.compile("U([0-9A-F]{6})");

  /**
   * The attribute name as the browser keeps it: the spec parser's DOM (name policy ALTER_INFOSET)
   * spells each character an XML name can't hold as {@code U} and six hex digits ({@code :href} is
   * {@code U00003Ahref}).
   */
  static String unescapeName(String name) {
    Matcher m = ESCAPED_NAME_CHAR.matcher(name);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(m.group(1), 16)))));
    }
    return m.appendTail(sb).toString();
  }

  /**
   * {@link #unescapeName} of the attribute's name, with its {@code xml:} prefix: the spec parser's
   * DOM names SVG's {@code xml:space} {@code space}, in the XML namespace.
   */
  static String attrName(org.w3c.dom.Node a) {
    String n = unescapeName(a.getNodeName());
    return "http://www.w3.org/XML/1998/namespace".equals(a.getNamespaceURI()) && n.indexOf(':') < 0 ? "xml:" + n : n;
  }

  static String wrap(String body) {
    return "<!DOCTYPE html><html><head></head><body>" + body + "</body></html>";
  }

  static Element target(Document d) {
    NodeList all = d.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if (e.hasAttribute("data-tsweep")) {
        return e;
      }
    }
    return null;
  }

  static Set<String> attrNames(Element e) {
    Set<String> s = new TreeSet<>();
    if (e != null) {
      for (int i = 0; i < e.getAttributes().getLength(); i++) {
        s.add(e.getAttributes().item(i).getNodeName());
      }
    }
    return s;
  }

  static String shape(Document d) {
    StringBuilder sb = new StringBuilder();
    NodeList all = d.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      sb.append(e.getNamespaceURI()).append(':').append(e.getLocalName()).append(' ');
    }
    return sb.toString();
  }

  static String render(TemplateEntry entry, Map<String, ?> model) {
    StringOutput out = new StringOutput();
    entry.renderer().render(model, RenderContext.defaults(), out);
    return out.toString();
  }

  static final Pattern ERROR_FILE = Pattern.compile("^([wj][0-9]{6})\\.html:");

  /** Compiles views in parallel batches; a failing batch drops the views its errors name and retries. */
  static Map<String, Compilation> compileAll(Map<String, String> templates) throws Exception {
    List<String> views = new ArrayList<>(templates.keySet());
    List<List<String>> batches = IntStream.range(0, (views.size() + BATCH - 1) / BATCH)
        .mapToObj(i -> views.subList(i * BATCH, Math.min(views.size(), (i + 1) * BATCH))).toList();
    Map<String, Compilation> accepted = new ConcurrentHashMap<>();
    POOL.submit(() -> batches.parallelStream().forEach(batch -> {
      List<String> todo = new ArrayList<>(batch);
      while (!todo.isEmpty()) {
        Compilation.Builder b = Compilation.builder();
        SWEEP_SOURCES.forEach(b::java);
        todo.forEach(v -> b.template(v + ".html", templates.get(v)));
        Compilation c = b.compile();
        if (c.success()) {
          todo.forEach(v -> accepted.put(v, c));
          return;
        }
        Set<String> bad = new HashSet<>();
        for (String e : c.errors()) {
          Matcher m = ERROR_FILE.matcher(e);
          if (m.find()) {
            bad.add(m.group(1));
          }
        }
        List<String> rest = todo.stream().filter(v -> !bad.contains(v)).toList();
        if (rest.size() == todo.size()) {
          for (String v : todo) {
            Compilation.Builder one = Compilation.builder().template(v + ".html", templates.get(v));
            SWEEP_SOURCES.forEach(one::java);
            Compilation alone = one.compile();
            if (alone.success()) {
              accepted.put(v, alone);
            }
          }
          return;
        }
        todo = new ArrayList<>(rest);
      }
    })).get();
    return accepted;
  }

  static String esc(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (c < 0x20 || c == 0x7f || (c >= 0x2028 && c <= 0x2029) || Character.isSurrogate(c)) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.length() > 400 ? sb.substring(0, 400) + "..." : sb.toString();
  }

  @SafeVarargs
  static <T> List<T> concat(List<T>... ls) {
    List<T> out = new ArrayList<>();
    for (List<T> l : ls) {
      out.addAll(l);
    }
    return out;
  }
}
