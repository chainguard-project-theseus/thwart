package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
import java.io.StringReader;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

class ContextCheckTest {

  /**
   * Stand-in for #318's {@code vnu-1.4} parser so this PR and #318 merge in either order.
   * TODO(#318): replace with SpecParsers (both engines) once it is on main.
   */
  private static final SpecParser VNU_14 =
      new SpecParser() {
        @Override
        public String name() {
          return "vnu-1.4";
        }

        @Override
        public Document parse(String html, boolean scripting) {
          try {
            HtmlDocumentBuilder builder = new HtmlDocumentBuilder(XmlViolationPolicy.ALLOW);
            builder.setScriptingEnabled(scripting);
            builder.setIgnoringComments(false);
            // Names such as :title aren't valid in a namespace-aware DOM; the parser escapes them.
            builder.setNamePolicy(XmlViolationPolicy.ALTER_INFOSET);
            return builder.parse(new InputSource(new StringReader(html)));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
      };

  private static final List<SpecParser> PARSERS = List.of(VNU_14);

  private static final String FP = Fingerprint.value(1);

  private static String text(String v) {
    return esc(Escapers::text, v);
  }

  private static String rcdata(String v) {
    return esc(Escapers::rcdata, v);
  }

  private static String attr(String v) {
    return esc(Escapers::attr, v);
  }

  private static String json(String v) {
    return esc(Escapers::json, v);
  }

  private static String jsonAttr(String v) {
    return esc(Escapers::jsonAttr, v);
  }

  private static String esc(BiConsumer<StringOutput, Object> escaper, String v) {
    StringOutput out = new StringOutput();
    escaper.accept(out, v);
    return out.toString();
  }

  private static List<Finding> check(String body) {
    return ContextCheck.check("<!DOCTYPE html><html><body>" + body + "</body></html>", PARSERS);
  }

  private static void assertMismatch(String body, String... whereFragments) {
    List<Finding> findings = check(body);
    assertFalse(findings.isEmpty(), "expected CONTEXT_MISMATCH for " + body);
    for (Finding f : findings) {
      assertEquals(Finding.Kind.CONTEXT_MISMATCH, f.kind(), f.toString());
      assertEquals("vnu-1.4", f.engine());
      assertEquals(FP, f.value());
    }
    String all = findings.toString();
    for (String w : whereFragments) {
      assertTrue(all.contains(w), "expected '" + w + "' in " + all);
    }
  }

  private static void assertClean(String body) {
    assertEquals(List.of(), check(body), body);
  }

  // ---- the fingerprint table ----

  @Test
  void everyEscaperClassifiesBackToItsContext() {
    Set<OutputContext> expected = EnumSet.noneOf(OutputContext.class);
    for (OutputContext c : OutputContext.values()) {
      if (c.escaper() != null && c != OutputContext.REFRESH) {
        expected.add(c);
      }
    }
    assertEquals(expected, Fingerprint.contexts());
    for (OutputContext c : Fingerprint.contexts()) {
      for (boolean decoded : new boolean[] {false, true}) {
        String core = Fingerprint.core(c, decoded);
        assertTrue(core.startsWith(Fingerprint.marker(1)), c + ": " + core);
        assertTrue(Fingerprint.classify(core, 0, decoded).contains(c), c + " decoded=" + decoded);
      }
    }
  }

  /**
   * The only contexts that share a fingerprint are those whose escapers write it byte for byte
   * the same (see {@link Fingerprint}): the entity-encoding family, and json/jsonAttr once decoded.
   * A new collision fails here, so the fingerprint gets fixed.
   */
  @Test
  void onlyIdenticalEscapersCollide() {
    Set<OutputContext> entity =
        EnumSet.of(OutputContext.RCDATA, OutputContext.ATTRIBUTE, OutputContext.URL,
            OutputContext.URL_LIST, OutputContext.RESOURCE_URL);
    Set<Set<OutputContext>> raw = new HashSet<>();
    Set<Set<OutputContext>> decoded = new HashSet<>();
    for (OutputContext c : Fingerprint.contexts()) {
      raw.add(Fingerprint.classify(Fingerprint.core(c, false), 0, false));
      decoded.add(Fingerprint.classify(Fingerprint.core(c, true), 0, true));
    }
    assertEquals(
        Set.of(EnumSet.of(OutputContext.TEXT), entity, EnumSet.of(OutputContext.SCRIPT),
            EnumSet.of(OutputContext.SCRIPT_ATTRIBUTE)),
        raw);
    assertEquals(
        Set.of(EnumSet.of(OutputContext.TEXT), entity,
            EnumSet.of(OutputContext.SCRIPT, OutputContext.SCRIPT_ATTRIBUTE)),
        decoded);
    // The distinct families really differ in what the parser sees, not only in the bytes.
    assertFalse(Fingerprint.core(OutputContext.TEXT, true).equals(Fingerprint.core(OutputContext.RCDATA, true)));
  }

  /**
   * Link cores: a path segment encodes everything the escapers touch, so it reads the same in every
   * context; a query value keeps {@code '} and {@code /}, which still split the entity family (with
   * TEXT: no braces left to break), JSON, and REFRESH (which percent-encodes {@code '}).
   */
  @Test
  void linkCoresCollideOnlyWhereIdentical() {
    Set<OutputContext> all = EnumSet.noneOf(OutputContext.class);
    for (OutputContext c : OutputContext.values()) {
      if (c.escaper() != null) {
        all.add(c);
      }
    }
    Set<OutputContext> entity =
        EnumSet.of(OutputContext.TEXT, OutputContext.RCDATA, OutputContext.ATTRIBUTE,
            OutputContext.URL, OutputContext.URL_LIST, OutputContext.RESOURCE_URL);
    for (boolean decoded : new boolean[] {false, true}) {
      Set<Set<OutputContext>> path = new HashSet<>();
      Set<Set<OutputContext>> query = new HashSet<>();
      for (OutputContext c : all) {
        List<String> cores = Fingerprint.linkCores(c, decoded);
        assertTrue(cores.get(0).startsWith(Fingerprint.marker(1) + "%3C"), c + ": " + cores);
        path.add(Fingerprint.classify(cores.get(0), 0, decoded));
        query.add(Fingerprint.classify(cores.get(1), 0, decoded));
      }
      assertEquals(Set.of(all), path, "decoded=" + decoded);
      assertEquals(
          Set.of(entity, EnumSet.of(OutputContext.SCRIPT, OutputContext.SCRIPT_ATTRIBUTE),
              EnumSet.of(OutputContext.REFRESH)),
          query, "decoded=" + decoded);
    }
  }

  @Test
  void unknownWritingIsUnclassified() {
    assertEquals(Set.of(), Fingerprint.classify(FP, 0, false));
    assertEquals(Set.of(), Fingerprint.classify("tqf0001", 0, true));
    // Once parsed, an unescaped value reads like an entity-escaped one: only the render's bytes
    // tell them apart, which is why ContextCheck classifies both.
    assertEquals(Fingerprint.classify(Fingerprint.core(OutputContext.ATTRIBUTE, false), 0, false),
        Fingerprint.classify(FP, 0, true));
  }

  @Test
  void markersAreFixedWidth() {
    assertEquals("tqf0001", Fingerprint.marker(1));
    assertEquals("tqf9999", Fingerprint.marker(9999));
    assertEquals("tqf0042<'\"&{{/`\n", Fingerprint.value(42));
  }

  // ---- negatives: the wrong escaper for where the parser puts the value ----

  /** #306 §1: the JSON value of an SVG script lands in HTML text once {@code <p>} ends the SVG. */
  @Test
  void jsonInHtmlTextAfterSvgBreakout() {
    assertMismatch("<p>x</p><svg><script>// <p>\nvar a = " + json(FP) + ";</script></svg>",
        "/html/body/p text", "SCRIPT", "HTML element text");
  }

  /** #277: an RCDATA-escaped value in SVG {@code <title>}, which is foreign markup, not RCDATA. */
  @Test
  void rcdataInSvgTitle() {
    assertMismatch("<svg><title>" + rcdata(FP) + "</title></svg>",
        "/html/body/svg[svg]/title[svg] text", "RCDATA", "foreign element text");
  }

  @Test
  void textInScript() {
    assertMismatch("<script>var a = " + text(FP) + ";</script>", "/html/body/script text", "TEXT",
        "raw-text element text");
    assertMismatch("<svg><script>var a = " + text(FP) + ";</script></svg>",
        "/html/body/svg[svg]/script[svg] text");
  }

  @Test
  void otherWrongPlaces() {
    // TEXT escaping in RCDATA shows the comment as text.
    assertMismatch("<title>" + text(FP) + "</title>", "RCDATA element text");
    // Entity escaping in flow text doesn't break {{.
    assertMismatch("<p>" + attr(FP) + "</p>", "/html/body/p text");
    // Entity escaping in an event handler, srcdoc or a framework directive.
    assertMismatch("<p onclick=\"" + attr(FP) + "\">x</p>", "/html/body/p/@onclick");
    assertMismatch("<iframe srcdoc=\"" + attr(FP) + "\"></iframe>", "@srcdoc");
    assertMismatch("<p x-text=\"" + attr(FP) + "\">x</p>", "@x-text");
    // JSON in a non-JS script, and in a plain attribute.
    assertMismatch("<script type=\"text/template\">" + json(FP) + "</script>", "script text");
    assertMismatch("<p title=\"" + jsonAttr(FP) + "\">x</p>", "/html/body/p/@title");
    // noscript is raw text with scripting on and markup with scripting off.
    assertMismatch("<noscript>" + text(FP) + "</noscript>", "scripting-on /html/body/noscript text");
  }

  /**
   * Places an escaper's output is wrong in that the first version accepted or never tried: code
   * attributes beyond on*, refresh content, JSON bindings of URL targets, text the parser
   * foster-parents out of a table (its {@code {<!---->{} break is split: the comment stays in the
   * table, the braces join in the text before it), SVG {@code <textarea>} and a MathML
   * {@code <title>}.
   */
  @Test
  void moreWrongPlaces() {
    assertMismatch("<p hx-vals=\"" + attr(FP) + "\">x</p>", "@hx-vals");
    assertMismatch("<p data-hx-trigger=\"" + attr(FP) + "\">x</p>", "@data-hx-trigger");
    assertMismatch("<p _=\"" + attr(FP) + "\">x</p>", "@_");
    assertMismatch("<p ng:click=\"" + attr(FP) + "\">x</p>", "@ng:click");
    assertMismatch("<x-c params=\"" + attr(FP) + "\">x</x-c>", "@params");
    assertMismatch("<script type=\"" + attr(FP) + "\"></script>", "@type");
    assertMismatch("<meta http-equiv=\"refresh\" content=\"0;url=/?q=" + attr(FP) + "\">", "@content");
    assertMismatch("<a :href=\"" + jsonAttr(FP) + "\">x</a>", "@:href");
    assertMismatch("<p v-bind:title.prop=\"" + jsonAttr(FP) + "\">x</p>", "@v-bind:title.prop");
    assertMismatch("<table>" + text(FP) + "<tr><td>x</td></tr></table>", "/html/body text");
    assertMismatch("<svg><textarea>" + rcdata(FP) + "</textarea></svg>", "textarea[svg] text");
    assertMismatch("<math><annotation-xml><title>" + rcdata(FP) + "</title></annotation-xml></math>",
        "title[math] text");
    // A query-encoded link value is still entity-escaped: wrong in an event handler.
    String query = new DefaultLinks("").build("/", new String[] {"q"}, new Object[] {FP});
    assertTrue(query.contains("%3C'"), query);
    assertMismatch("<p onclick=\"go('" + attr(query) + "')\">x</p>", "@onclick");
  }

  @Test
  void unescapedValueIsUnclassified() {
    assertMismatch("<p>" + FP + "</p>", "unclassified escaper");
  }

  @Test
  void parserThatDropsCommentsIsRefused() {
    SpecParser dropping =
        new SpecParser() {
          @Override
          public String name() {
            return "no-comments";
          }

          @Override
          public Document parse(String html, boolean scripting) {
            try {
              return new HtmlDocumentBuilder(XmlViolationPolicy.ALLOW)
                  .parse(new InputSource(new StringReader(html)));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    IllegalStateException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> ContextCheck.check("<p>" + text(FP) + "</p>", List.of(dropping)));
    assertTrue(e.getMessage().contains("drops comments"), e.getMessage());
  }

  @Test
  void markerInCommentOrNameOrLost() {
    assertMismatch("<p><!-- " + text(FP) + " --></p>", "in a comment");
    assertMismatch("<p " + Fingerprint.marker(1) + "=x>y</p>", "in an attribute name");
    // In the render but dropped by the parser: a bogus end tag is ignored.
    assertMismatch("<p></" + Fingerprint.marker(1) + "></p>", "not in the parsed tree");
  }

  // ---- clean: each context where it belongs ----

  @Test
  void everyContextInItsPlaceIsClean() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("TEXT", "<p>" + text(FP) + "</p>");
    cases.put("TEXT svg", "<svg><text>" + text(FP) + "</text></svg>");
    cases.put("TEXT math", "<math><mi>" + text(FP) + "</mi></math>");
    cases.put("TEXT foreignObject", "<svg><foreignObject><p>" + text(FP) + "</p></foreignObject></svg>");
    cases.put("RCDATA title", "<title>" + rcdata(FP) + "</title>");
    cases.put("RCDATA textarea", "<textarea>" + rcdata(FP) + "</textarea>");
    cases.put("ATTRIBUTE", "<p title=\"" + attr(FP) + "\">x</p>");
    cases.put("URL", "<a href=\"" + esc(Escapers::url, FP) + "\">x</a>");
    cases.put("URL_LIST", "<svg><animate values=\"" + esc(Escapers::urlList, FP) + "\"/></svg>");
    cases.put("RESOURCE_URL", "<script src=\"" + esc(Escapers::resourceUrl, FP) + "\"></script>");
    cases.put("SCRIPT", "<script>var a = " + json(FP) + ";</script>");
    cases.put("SCRIPT module", "<script type=\"module\">var a = " + json(FP) + ";</script>");
    cases.put("SCRIPT svg", "<svg><script>var a = " + json(FP) + ";</script></svg>");
    cases.put("SCRIPT_ATTRIBUTE", "<p :title=\"" + jsonAttr(FP) + "\">x</p>");
    cases.put("SCRIPT_ATTRIBUTE x-bind", "<p x-bind:title=\"" + jsonAttr(FP) + "\">x</p>");
    cases.put("SCRIPT json", "<script type=\"application/json\">" + json(FP) + "</script>");
    cases.put("SCRIPT ld+json", "<script type=\"application/ld+json\">{\"a\": " + json(FP) + "}</script>");
    cases.put("concatenated", "<p>a" + text(FP + "b") + "</p><script>var a = " + json("x" + FP + "y") + ";</script>");
    for (Map.Entry<String, String> e : cases.entrySet()) {
      assertEquals(List.of(), check(e.getValue()), e.getKey() + ": " + e.getValue());
    }
  }

  @Test
  void noMarkersNoFindings() {
    assertClean("<p>nothing</p>");
  }

  @Test
  void manySlotsEachChecked() {
    String fp2 = Fingerprint.value(2);
    List<Finding> findings =
        check("<p>" + text(FP) + "</p><script>var a = " + text(fp2) + ";</script>");
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(fp2, findings.get(0).value());
  }

  // ---- end to end: the compiler's real output is clean ----

  /** The holes, compiled in one javac run. */
  private static final List<String> COMPILED =
      List.of(
          "<p>[[${s}]]</p>",
          "<p t:text=\"${s}\">x</p>",
          "<title>[[${s}]]</title>",
          "<textarea t:text=\"${s}\">x</textarea>",
          "<p t:title=\"${s}\">x</p>",
          "<a t:href=\"${s}\">x</a>",
          "<script>var a = [[${s}]];</script>",
          "<svg><text>[[${s}]]</text></svg>",
          "<svg><script>var a = [[${s}]];</script></svg>",
          "<script type=\"application/json\">[[${s}]]</script>",
          "<a t:href=\"@{/x/{id}(id=${s})}\">x</a>",
          "<a t:href=\"@{/x(q=${s})}\">x</a>",
          "<script t:src=\"@{/x/{id}.js(id=${s})}\"></script>",
          "<script>var u = [[@{/x(q=${s})}]];</script>",
          "<p t:text=\"@{/x(q=${s})}\">x</p>",
          "<meta http-equiv=\"refresh\" t:content=\"'3;url=' + @{/x(q=${s})}\">");

  @Test
  void compiledTemplatesAreClean() {
    Compilation.Builder b = Compilation.builder();
    for (int i = 0; i < COMPILED.size(); i++) {
      b.template("page" + i + ".html", "<div t:params=\"String s\">" + COMPILED.get(i) + "</div>");
    }
    Compilation c = b.compile().assertSuccess();
    for (int i = 0; i < COMPILED.size(); i++) {
      String render = c.render("page" + i, Map.of("s", FP));
      assertTrue(render.contains(Fingerprint.marker(1)), render);
      assertEquals(List.of(), ContextCheck.check(render, PARSERS), COMPILED.get(i) + " -> " + render);
    }
  }
}
