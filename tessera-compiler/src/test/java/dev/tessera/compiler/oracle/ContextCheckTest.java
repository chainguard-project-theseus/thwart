package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.StringOutput;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

class ContextCheckTest {
  private static final List<SpecParser> PARSERS = List.of(SpecParsers.named(SpecParsers.VNU_26));

  private static final String FP = Fingerprint.value(1);

  private static String text(String v) {
    return esc(Escapers::text, v);
  }

  private static String attr(String v) {
    return esc(Escapers::attr, v);
  }

  private static String json(String v) {
    return esc(Escapers::json, v);
  }

  /** JSON written into an attribute value (attribute-encoded), as the deleted jsonAttr did (#599). */
  private static String jsonInAttribute(String v) {
    return attr(json(v));
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
      assertEquals("vnu-26", f.engine());
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
   * the same (see {@link Fingerprint}): the entity-encoding family.
   * A new collision fails here, so the fingerprint gets fixed.
   */
  @Test
  void onlyIdenticalEscapersCollide() {
    Set<OutputContext> entity =
        EnumSet.of(OutputContext.TEXT, OutputContext.ATTRIBUTE, OutputContext.URL,
            OutputContext.RESOURCE_URL);
    Set<Set<OutputContext>> raw = new HashSet<>();
    Set<Set<OutputContext>> decoded = new HashSet<>();
    for (OutputContext c : Fingerprint.contexts()) {
      raw.add(Fingerprint.classify(Fingerprint.core(c, false), 0, false));
      decoded.add(Fingerprint.classify(Fingerprint.core(c, true), 0, true));
    }
    assertEquals(
        Set.of(entity, EnumSet.of(OutputContext.SCRIPT)), raw);
    assertEquals(
        Set.of(entity, EnumSet.of(OutputContext.SCRIPT)), decoded);
  }

  /**
   * Link cores: a path segment encodes everything the escapers touch, so it reads the same in every
   * context; a query value keeps {@code '} and {@code /}, which still split the entity family,
   * JSON, and REFRESH (which percent-encodes {@code '}).
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
        EnumSet.of(OutputContext.TEXT, OutputContext.ATTRIBUTE,
            OutputContext.URL, OutputContext.RESOURCE_URL);
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
          Set.of(entity, EnumSet.of(OutputContext.SCRIPT), EnumSet.of(OutputContext.REFRESH)),
          query, "decoded=" + decoded);
    }
  }

  /**
   * No context writes JSON into an attribute value since #599 (jsonAttr is deleted), so the render of
   * a JSON value in a Vue/Alpine binding is unclassified, a mismatch wherever it is (fail closed).
   */
  @Test
  void jsonInABindingIsAMismatch() {
    assertMismatch("<p :title=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
    assertMismatch("<p v-bind:title=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
    assertMismatch("<p x-bind:title=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
    assertMismatch("<p :data_foo=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
    assertMismatch("<a :href=\"" + jsonInAttribute(FP) + "\">x</a>", "unclassified escaper");
    assertMismatch("<p v-bind:title.prop=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
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

  @Test
  void textInScript() {
    assertMismatch("<script>var a = " + text(FP) + ";</script>", "/html/body/script text", "TEXT",
        "raw-text element text");
    assertMismatch("<svg><script>var a = " + text(FP) + ";</script></svg>",
        "/html/body/svg[svg]/script[svg] text");
  }

  @Test
  void otherWrongPlaces() {
    // Entity escaping in an event handler, srcdoc or a name outside HTML.
    assertMismatch("<p onclick=\"" + attr(FP) + "\">x</p>", "/html/body/p/@onclick");
    // An event handler is not plain at all, so no neutral value would help: no outside-HTML note.
    assertFalse(check("<p onclick=\"" + attr(FP) + "\">x</p>").toString().contains("outside HTML"));
    assertMismatch("<iframe srcdoc=\"" + attr(FP) + "\"></iframe>", "@srcdoc");
    assertMismatch("<p x-text=\"" + attr(FP) + "\">x</p>", "@x-text");
    // JSON in a non-JS script, and in a plain attribute.
    assertMismatch("<script type=\"text/template\">" + json(FP) + "</script>", "script text");
    assertMismatch("<p title=\"" + jsonInAttribute(FP) + "\">x</p>", "unclassified escaper");
    // noscript is raw text with scripting on and markup with scripting off.
    assertMismatch("<noscript>" + text(FP) + "</noscript>", "scripting-on /html/body/noscript text");
  }

  /**
   * Places an escaper's output is wrong in that the first version accepted or never tried: attributes
   * that decide how other content is read, and refresh content.
   */
  @Test
  void moreWrongPlaces() {
    assertMismatch("<script type=\"" + attr(FP) + "\"></script>", "@type");
    assertMismatch("<meta http-equiv=\"refresh\" content=\"0;url=/?q=" + attr(FP) + "\">", "@content");
    // An SVG presentation attribute that takes a CSS url() is CSS, like style; an animation's fill
    // is its timing attribute, and an HTML element's fill is plain.
    assertMismatch("<svg><rect fill=\"" + attr(FP) + "\"/></svg>", "@fill");
    assertMismatch("<svg><g CLIP-PATH=\"" + attr(FP) + "\"/></svg>", "@clip-path");
    assertMismatch("<svg><path marker-end=\"" + attr(FP) + "\"/></svg>", "@marker-end");
    assertMismatch("<svg><path marker=\"" + attr(FP) + "\"/></svg>", "@marker");
    assertClean("<svg><rect><animate fill=\"" + attr(FP) + "\"/></rect></svg>");
    assertClean("<svg><rect><animateTransform fill=\"" + attr(FP) + "\"/><animateMotion fill=\"" + attr(FP) + "\"/></rect></svg>");
    assertMismatch("<svg><rect><animate stroke=\"" + attr(FP) + "\"/></rect></svg>", "@stroke");
    assertClean("<p fill=\"" + attr(FP) + "\">x</p>");
    // An animation of one of them sets CSS: its values are CSS too, and its attributeName decides that.
    assertMismatch("<svg><rect><animate attributeName=\"fill\" values=\"" + attr(FP) + "\"/></rect></svg>", "@values");
    assertMismatch("<svg><rect><set attributeName=\"MASK\" to=\"" + attr(FP) + "\"/></rect></svg>", "@to");
    assertMismatch("<svg><rect><animate attributeName=\"" + attr(FP) + "\"/></rect></svg>", "animate[svg]/@attribute");
    assertClean("<svg><a><set attributeName=\"href\" to=\"" + attr(FP) + "\"/></a></svg>");
    assertClean("<svg><rect><animate attributeName=\"opacity\" values=\"" + attr(FP) + "\"/></rect></svg>");
    // A query-encoded link value is still entity-escaped: wrong in an event handler.
    String query = new DefaultLinks("").build("/", new String[] {"q"}, new Object[] {FP});
    assertTrue(query.contains("%3C'"), query);
    assertMismatch("<p onclick=\"go('" + attr(query) + "')\">x</p>", "@onclick");
  }

  /**
   * #608: the oracle reads its own copy of the standard names, not the compiler's file, and the two
   * agree name for name and source for source; a name added to only one of them fails here.
   */
  @Test
  void standardNamesAreTheOraclesOwnCopy() {
    assertFalse(HostileSinks.STANDARD.isEmpty());
    assertEquals(HostileSinks.standardNames(HostileSinks.COMPILER_STANDARD), HostileSinks.STANDARD);
    Map<String, Set<String>> bad = new LinkedHashMap<>(HostileSinks.STANDARD);
    bad.put("x-html", Set.of("html"));
    assertFalse(bad.equals(HostileSinks.standardNames(HostileSinks.COMPILER_STANDARD)));
    assertTrue(ContextCheck.outsideHtml("x-html"));
  }

  /**
   * #608: a name outside the standard HTML, SVG, MathML and ARIA list takes only a framework-neutral
   * value (#583 rule 3), whichever code reads it. The check names no framework, so names no list
   * knows ({@code data-on-click}, {@code x_ng-bind}) are held to it too.
   */
  @Test
  void stringOutsideHtmlIsAMismatch() {
    for (String name : List.of("data-on-click", "x_ng-bind", "data-x", "my-attr", "hx-vals", "data-hx-trigger",
        "x-text", "v-html", "_", "ng:click", "data--ng-click", "data-data-ng-click", ":title", "@click", "data-bind")) {
      assertMismatch("<p " + name + "=\"" + attr(FP) + "\">x</p>", "@" + name, "outside HTML");
    }
    assertMismatch("<x-c params=\"" + attr(FP) + "\">x</x-c>", "@params", "outside HTML");
    // Browsers fold A-Z only: KELVIN SIGN K is not k, so this is not the standard kind.
    assertMismatch("<p Kind=\"" + attr(FP) + "\">x</p>", "@Kind", "outside HTML");
  }

  /**
   * #608: a link outside HTML is clean only if the whole rendered value has the neutral path shape:
   * one leading {@code /}, no query, fragment or {@code --}.
   */
  @Test
  void linkOutsideHtmlMustBeANeutralPath() {
    DefaultLinks links = new DefaultLinks("");
    String path = links.build("/x/{id}", new String[] {"id"}, new Object[] {FP});
    assertMismatch("<p data-u=\"" + attr(links.build("/x", new String[] {"q"}, new Object[] {FP})) + "\">x</p>",
        "@data-u", "outside HTML");
    assertMismatch("<p data-u=\"" + attr(links.build("/a--b/{id}", new String[] {"id"}, new Object[] {FP}))
        + "\">x</p>", "@data-u");
    assertMismatch("<p data-u=\"/" + attr(path) + "\">x</p>", "@data-u");
    assertMismatch("<p data-u=\"" + attr(path) + "#top\">x</p>", "@data-u");
    assertMismatch("<p data-u=\"x" + attr(path) + "\">x</p>", "@data-u");
    // The shape is checked on the parsed value: character references don't hide -- or a leading //.
    assertMismatch("<p data-u=\"/a&#45;&#x2D;b" + attr(path) + "\">x</p>", "@data-u");
    assertMismatch("<p data-u=\"&#47;" + attr(path) + "\">x</p>", "@data-u");
  }

  /**
   * #608, the positive controls: a neutral path outside HTML, under any name (this is the case the
   * check accepts that the framework lists refused; #583's research found a value of this shape
   * runs nothing in the frameworks it tested), and any escaped string in a standard name.
   */
  @Test
  void neutralOutsideHtmlAndStringsInStandardNamesAreClean() {
    String path = new DefaultLinks("").build("/x/{id}", new String[] {"id"}, new Object[] {FP});
    assertClean("<p data-u=\"" + attr(path) + "\" hx-get=\"" + attr(path) + "\" :x=\"" + attr(path) + "\">x</p>");
    assertClean("<p hx-vals=\"" + attr(path) + "\" x-text=\"" + attr(path) + "\" ng-init=\"" + attr(path) + "\">x</p>");
    assertClean("<x-c params=\"" + attr(path) + "\">x</x-c>");
    assertClean("<p title=\"" + attr(FP) + "\" ARIA-LABEL=\"" + attr(FP) + "\">x</p>");
    assertClean("<svg><rect><animate fill=\"" + attr(FP) + "\"/></rect></svg>");
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
            Document d = SpecParsers.named(SpecParsers.VNU_26).parse(html, scripting);
            dropComments(d);
            return d;
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
    cases.put("TEXT title", "<title>" + text(FP) + "</title>");
    cases.put("TEXT textarea", "<textarea>" + text(FP) + "</textarea>");
    cases.put("ATTRIBUTE", "<p title=\"" + attr(FP) + "\">x</p>");
    cases.put("URL", "<a href=\"" + esc(Escapers::url, FP) + "\">x</a>");
    cases.put("RESOURCE_URL", "<script src=\"" + esc(Escapers::resourceUrl, FP) + "\"></script>");
    cases.put("SCRIPT", "<script>var a = " + json(FP) + ";</script>");
    cases.put("SCRIPT module", "<script type=\"module\">var a = " + json(FP) + ";</script>");
    cases.put("SCRIPT svg", "<svg><script>var a = " + json(FP) + ";</script></svg>");
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
          "<script type=\"application/json\">[[${s}]]</script>",
          "<a t:href=\"@{/x/{id}(id=${s})}\">x</a>",
          "<a t:href=\"@{/x(q=${s})}\">x</a>",
          "<p t:data-id=\"@{/x/{id}(id=${s})}\" t:hx-get=\"@{/x/{id}(id=${s})}\">x</p>",
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

  /** Removes every comment node under {@code n}. */
  private static void dropComments(Node n) {
    for (Node c = n.getFirstChild(); c != null; ) {
      Node next = c.getNextSibling();
      if (c.getNodeType() == Node.COMMENT_NODE) {
        n.removeChild(c);
      } else {
        dropComments(c);
      }
      c = next;
    }
  }
}
