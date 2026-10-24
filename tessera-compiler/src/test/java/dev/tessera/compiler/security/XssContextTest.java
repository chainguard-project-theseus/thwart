package dev.tessera.compiler.security;

import static dev.tessera.compiler.security.HostileStrings.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * XSS vectors in every output context, end to end: one template puts the same hostile string
 * into every context Tessera allows (directly, through messages, links and form binding), and an
 * independent {@link ContextOracle} parses each output slot the way a browser would. Contexts
 * that cannot be escaped (style, event handlers, srcdoc, computed script types, resource URLs
 * from strings, raw output of non-SafeHtml) must fail to build.
 */
class XssContextTest {

  private static final String MARK = "ZZZ";

  /** How an output slot is checked. */
  enum Kind {
    /** Element text: {@code {{} is broken with empty comments (#248). */
    TEXT,
    /** Element text in the middle of one value (a literal substitution): its ends are not edges. */
    TEXT_PART,
    HTML,
    URL,
    JSON,
    PATH_SEGMENT,
    QUERY
  }

  /** One template line and the kinds of the slots it renders, in output order. */
  record Line(String markup, Kind... kinds) {}

  static final List<Line> LINES =
      List.of(
          new Line("<p t:text=\"s\">x</p>", Kind.TEXT),
          new Line("<p>a [[s]] b</p>", Kind.TEXT),
          new Line("<title>[[s]]</title>", Kind.HTML),
          new Line("<textarea t:text=\"s\"></textarea>", Kind.HTML),
          new Line("<xmp>[[s]]</xmp><noscript>[[s]]</noscript><noembed>[[s]]</noembed>", Kind.TEXT, Kind.TEXT, Kind.TEXT),
          new Line("<iframe>[[s]]</iframe>", Kind.TEXT),
          new Line("<i t:title=\"s\" t:attr=\"data-x=s\">x</i>", Kind.HTML, Kind.HTML),
          new Line("<i t:class=\"s\">x</i><i class=\"c\" t:classappend=\"'k' + s\">y</i>", Kind.HTML, Kind.HTML),
          new Line("<input t:value=\"s\"><img t:alt=\"s\">", Kind.HTML, Kind.HTML),
          new Line("<a t:href=\"s\">x</a><a t:attr=\"HREF=s\">y</a>", Kind.URL, Kind.URL),
          new Line("<form t:action=\"s\"></form><button t:formaction=\"s\">x</button>", Kind.URL, Kind.URL),
          new Line("<img t:src=\"s\"><video t:poster=\"s\"></video><q t:cite=\"s\">q</q>", Kind.URL, Kind.URL, Kind.URL),
          new Line("<svg><a t:xlink:href=\"s\"><text>x</text></a><image t:href=\"s\"/></svg>", Kind.URL, Kind.URL),
          new Line(
              "<svg><a><animate attributeName=\"href\" t:values=\"s\"/><set attributeName=\"href\" t:to=\"s\"/>"
                  + "<animate attributeName=\"href\" t:from=\"s\" t:by=\"s\"/><SET attributeName=\"href\" t:attr=\"to=s\"/></a></svg>",
              Kind.URL, Kind.URL, Kind.URL, Kind.URL, Kind.URL),
          new Line("<a t:href=\"@{/o/{id}(id='x' + s, q=s)}\">x</a>", Kind.PATH_SEGMENT, Kind.QUERY),
          new Line("<script t:src=\"@{/js/{f}(f='x' + s)}\"></script>", Kind.PATH_SEGMENT),
          new Line("<p t:text=\"#{greet(s)}\">x</p><i t:title=\"#{greet(s)}\">y</i>", Kind.TEXT, Kind.HTML),
          new Line("<p t:text=\"|a ${s} b|\">x</p>", Kind.TEXT_PART),
          new Line("<script t:text=\"s\"></script>", Kind.JSON),
          new Line(
              "<script>var a = [[s]]; /*[[s]]*/ var t = `[[s]]`; var r = /[[s]]/; // [[s]]\n</script>",
              Kind.JSON, Kind.JSON, Kind.JSON, Kind.JSON, Kind.JSON),
          new Line("<SCRIPT type=\"application/json\">[[s]]</SCRIPT>", Kind.JSON),
          new Line(
              "<svg><title>[[s]]<script>var a = [[s]];</script></title><textarea t:text=\"s\"></textarea>"
                  + "<textarea><script>var b = [[s]];</script></textarea></svg>",
              // #277: SVG title/textarea are not RCDATA; a nested <script> is a real script.
              Kind.TEXT, Kind.JSON, Kind.TEXT, Kind.JSON),
          new Line(
              "<form method=\"post\" t:object=\"owner\"><input t:field=\"*{name}\"><span t:errors=\"*{name}\">e</span>"
                  + "<textarea t:field=\"*{name}\"></textarea></form>",
              // The first slot is the injected CSRF hidden-input value, before the fields. A
              // <textarea> is RCDATA: no comments there (#248).
              Kind.HTML, Kind.HTML, Kind.TEXT, Kind.HTML));

  private static Compilation compilation;
  private static List<Kind> kinds;
  private static Pattern skeleton;

  @BeforeAll
  static void compileOnce() {
    StringBuilder t = new StringBuilder("<div t:import=\"com.example.*\" t:params=\"String s, Owner owner\">\n");
    for (Line line : LINES) {
      t.append(line.markup()).append('\n');
    }
    t.append("</div>");
    compilation =
        Compilation.builder()
            .java("com.example.Owner", "package com.example; public record Owner(String name) {}")
            .messages("greet=Hi {0}!\n")
            .template("page.html", t.toString())
            .compile()
            .assertSuccess();
    kinds = kinds();
    skeleton = skeleton(kinds);
  }

  /** A request whose messages, form state and CSRF token all echo the hostile value. */
  private static RenderContext hostileContext(String s) {
    Forms forms =
        new Forms() {
          @Override
          public String value(String objectName, String path, Object currentValue) {
            return s;
          }

          @Override
          public List<String> errors(String objectName, String path) {
            return List.of(s);
          }

          @Override
          public Optional<CsrfToken> csrfToken() {
            return Optional.of(new CsrfToken("_csrf", s));
          }
        };
    return new RenderContext(Locale.ROOT, (key, args) -> String.valueOf(args[0]), new DefaultLinks(""), forms);
  }

  private static String render(String s) {
    Object owner = compilation.newInstance("com.example.Owner", s);
    return compilation.render("page", Map.of("s", s, "owner", owner), hostileContext(s));
  }

  /**
   * The output with a capture group per slot, from a render with a benign marker value. A JSON
   * slot is the body of a string literal (escapes included); other slots end at the next static
   * text, which always starts with a character their escaper encodes.
   */
  private static Pattern skeleton(List<Kind> kinds) {
    String out = render(MARK);
    String[] statics = out.split(MARK, -1);
    assertEquals(kinds.size(), statics.length - 1, () -> "slot count in\n" + out);
    StringBuilder re = new StringBuilder("^");
    for (int i = 0; i < statics.length; i++) {
      if (i > 0) {
        re.append(kinds.get(i - 1) == Kind.JSON ? "((?:[^\"\\\\]|\\\\.)*)" : "(.*?)");
      }
      re.append(Pattern.quote(statics[i]));
    }
    return Pattern.compile(re.append('$').toString(), Pattern.DOTALL);
  }

  private static List<Kind> kinds() {
    return LINES.stream().flatMap(l -> Stream.of(l.kinds())).toList();
  }

  static void assertCannotLeaveContext(String s) {
    String out = render(s);
    Matcher m = skeleton.matcher(out);
    if (!m.matches()) {
      fail("output structure changed for " + show(s) + ":\n" + out);
    }
    for (int i = 0; i < kinds.size(); i++) {
      String slot = m.group(i + 1);
      String problem =
          switch (kinds.get(i)) {
            case TEXT -> ContextOracle.text(slot, null);
            case TEXT_PART -> ContextOracle.text(slot, null, false);
            case HTML -> ContextOracle.html(slot, null);
            case URL -> ContextOracle.url(slot, s);
            case JSON -> ContextOracle.jsonString("\"" + slot + "\"", s);
            case PATH_SEGMENT ->
                ContextOracle.decodeHtml(slot).matches("[A-Za-z0-9%._~-]*") ? null : "unencoded path segment";
            case QUERY -> {
              String r = ContextOracle.html(slot, null);
              yield r != null || !ContextOracle.decodeHtml(slot).matches("[^&=+#? \"<>]*") ? "unencoded query value" : null;
            }
          };
      if (problem != null) {
        fail("slot " + (i + 1) + " (" + kinds.get(i) + ") for " + show(s) + ": " + problem + ": " + slot + "\n" + out);
      }
    }
  }

  @ParameterizedTest
  @FieldSource("dev.tessera.compiler.security.HostileStrings#VECTORS")
  void vectorCannotLeaveAnyContext(String vector) {
    assertCannotLeaveContext(vector);
  }

  @Test
  void randomStringsCannotLeaveAnyContext() {
    HostileStrings.forEachSeeded(2000, (rnd, i) -> assertCannotLeaveContext(HostileStrings.random(rnd, 12)));
  }

  @Test
  void dangerousUrlsAreReplacedEverywhere() {
    String out = render("javascript:alert(1)");
    assertTrue(out.contains("href=\"about:invalid#tessera\""), out);
    assertTrue(out.contains("values=\"about:invalid#tessera\""), out);
    assertTrue(out.contains(" to=\"about:invalid#tessera\""), out);
    assertTrue(!Pattern.compile("[ :](href|values|to|from|by|action|formaction|src|poster|cite)=\"javascript", Pattern.CASE_INSENSITIVE).matcher(out).find(), out);
  }

  @Test
  void scriptInlineInTemplateLiteralAndCommentIsInert() {
    String out = render("`;alert(1);/*${x}*/");
    assertTrue(
        out.contains("var t = `\"\\u0060;alert(1);\\/*\\u0024{x}*\\/\"`;"),
        out);
  }

  @Test
  void mixedCaseScriptEndTagIsNeverTreatedAsText() {
    // Browsers end <Script> at </sCrIpT> (#47): the content is a script context and the
    // markup after the end tag is HTML text again.
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:params=\"String s\"><Script>var a = [[s]];</sCrIpT><p>[[s]]</p></div>")
            .compile();
    c.assertSuccess();
    String out = c.render("page", Map.of("s", "</script><b>"));
    assertTrue(out.contains("<Script>var a = \"\\u003c\\/script\\u003e\\u003cb\\u003e\";</Script>"), out);
    assertTrue(out.contains("<p>&lt;/script&gt;&lt;b&gt;</p>"), out);
  }

  @Test
  void scriptInsideSvgOrMathTitleOrTextareaGetsJsonEscaping() {
    // #277: a browser runs a <script> nested in an SVG/MathML <title> or <textarea>; before the
    // fix these were RCDATA and the value was written as bare JavaScript.
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:params=\"String s\"><svg><title><script>var a = [[s]];</script></title>"
                    + "<textarea><script>var b = [[s]];</script></textarea></svg>"
                    + "<math><title><script>var c = [[s]];</script></title></math></div>")
            .compile();
    c.assertSuccess();
    assertEquals(
        "<div><svg><title><script>var a = \"alert(1)\";</script></title>"
            + "<textarea><script>var b = \"alert(1)\";</script></textarea></svg>"
            + "<math><title><script>var c = \"alert(1)\";</script></title></math></div>",
        c.render("page", Map.of("s", "alert(1)")));
  }

  @Test
  void fragmentFromInsideSvgWithTitleOrTextareaIsAnError() {
    // Inserted into HTML, the SVG <title>/<textarea> parsed as markup here would be RCDATA (#277).
    Compilation.builder()
        .template("page.html", "<div t:params=\"String s\"><svg><g t:fragment=\"g\"><title>t</title></g></svg></div>")
        .compile()
        .assertError("page.html:1:49:", "<title> in t:fragment 'g' is SVG/MathML content only inside its <svg> or <math>");
    Compilation.builder()
        .template(
            "page.html",
            "<div t:params=\"String s\"><section t:fragment=\"card(Fragment content)\"><div t:replace=\"content\">c</div>"
                + "</section><p t:replace=\"~{::card(~{::g})}\"><svg><g><textarea>[[s]]</textarea></g></svg></p></div>")
        .compile()
        .assertError("page.html:1:", "~{::g} selects an element inside <svg> or <math> with an SVG/MathML <textarea> in it");
    // A nested <svg> that drops its tag does not start SVG content where the fragment is inserted.
    Compilation.builder()
        .template(
            "page.html",
            "<div t:params=\"String s\"><svg><svg t:fragment=\"f\" t:remove=\"tag\"><textarea>t</textarea></svg></svg></div>")
        .compile()
        .assertError("page.html:1:", "<textarea> in t:fragment 'f' is SVG/MathML content only inside its <svg> or <math>");
  }

  @Test
  void fieldOnSvgTextareaIsElementText() {
    // t:field picks the textarea's context: RCDATA in HTML, element text in SVG (#277).
    Compilation c =
        Compilation.builder()
            .java("com.example.Owner", "package com.example; public record Owner(String name) {}")
            .template(
                "page.html",
                "<form t:import=\"com.example.*\" t:params=\"Owner owner\" t:object=\"owner\">"
                    + "<textarea t:field=\"*{name}\"></textarea><svg><textarea t:field=\"*{name}\"></textarea></svg></form>")
            .compile();
    c.assertSuccess();
    String out = c.render("page", Map.of("owner", c.newInstance("com.example.Owner", "{{x}}<b>")));
    assertTrue(out.contains(">{{x}}&lt;b&gt;</textarea><svg>"), out);
    assertTrue(out.contains("><!---->{<!---->{x}}&lt;b&gt;</textarea></svg>"), out);
  }

  @Test
  void tagRemovedSvgFragmentIsReportedOnce() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div><svg t:fragment=\"icon\" t:remove=\"tag\"><title>t</title></svg></div>")
            .compile();
    c.assertError("page.html:1:6:", "t:remove=\"tag\" is not supported on <svg>");
    assertEquals(1, c.errors().size(), () -> String.join("\n", c.errors()));
  }

  @Test
  void rawHtmlAndForeignInsertionErrorsNameTheRule() {
    Compilation.builder()
        .template("page.html", "<div t:params=\"String s\"><svg><g t:utext=\"html.sanitize(s)\"></g></svg></div>")
        .compile()
        .assertError("page.html:1:", "raw HTML (t:utext, [(...)]) inside SVG or MathML content is not supported");
    Compilation.builder()
        .template("page.html", "<div t:params=\"String s\"><svg><text>[(html.sanitize(s))]</text></svg></div>")
        .compile()
        .assertError("page.html:1:", "raw HTML (t:utext, [(...)]) inside SVG or MathML content is not supported");
    Compilation.builder()
        .template("page.html", "<div><p t:fragment=\"f\">x</p><svg><g t:insert=\"~{::f}\"></g></svg></div>")
        .compile()
        .assertError("page.html:1:", "t:insert inside SVG or MathML content needs a fragment whose root is an <svg> or <math>");
    Compilation.builder()
        .template("page.html", "<div><p t:fragment=\"f\">x</p><svg><g t:replace=\"~{::f}\"></g></svg></div>")
        .compile()
        .assertError("page.html:1:", "t:replace inside SVG or MathML content needs a fragment whose root is an <svg> or <math>");
    // Allowed: an <svg> fragment inside <svg>, raw HTML inside an integration point, and t:replace
    // on an <svg> placed in HTML.
    Compilation.builder()
        .template(
            "page.html",
            "<div t:params=\"String s\"><svg t:fragment=\"icon\"><circle r=\"1\"/></svg>"
                + "<svg><g t:insert=\"~{::icon}\"></g><foreignObject t:utext=\"html.sanitize(s)\"></foreignObject>"
                + "<title>[(html.sanitize(s))]</title></svg><svg t:replace=\"~{::icon}\"></svg>"
                + "<math><mtext t:utext=\"html.sanitize(s)\"></mtext></math></div>")
        .compile()
        .assertSuccess();
  }

  @Test
  void svgAndMathFragmentsKeepTheirNamespace() {
    // A fragment rooted at <svg>/<math> is SVG/MathML wherever it is inserted (#277).
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:params=\"String s\"><svg t:fragment=\"icon(String label)\"><title>[[label]]</title></svg>"
                    + "<svg><g t:fragment=\"dot\"><circle r=\"1\"/></g></svg>"
                    + "<p t:insert=\"~{::icon(s)}\"></p></div>")
            .compile();
    c.assertSuccess();
    assertEquals(
        "<div><svg><g><circle r=\"1\"/></g></svg><p><svg><title><!---->{<!---->{x}}</title></svg></p></div>",
        c.render("page", Map.of("s", "{{x}}")));
  }

  /** Markup that must fail to build: these contexts cannot be escaped. */
  static final List<String> REJECTED =
      List.of(
          "<i t:style=\"s\">x</i>",
          "<i t:attr=\"style=s\">x</i>",
          "<i t:STYLE=\"s\">x</i>",
          "<style>p { color: [[s]]; }</style>",
          "<STYLE>[[s]]</STYLE>",
          "<style t:text=\"s\"></style>",
          "<i t:onclick=\"s\">x</i>",
          "<i t:OnClick=\"s\">x</i>",
          "<i t:attr=\"onclick=s\">x</i>",
          "<i t:attr=\"ONMOUSEOVER=s\">x</i>",
          "<svg><a t:onbegin=\"s\"></a></svg>",
          "<body t:onload=\"s\"></body>",
          "<iframe t:srcdoc=\"s\"></iframe>",
          "<iframe t:attr=\"srcdoc=s\"></iframe>",
          "<script t:type=\"s\">var a = 1;</script>",
          "<script t:attr=\"type=s\">var a = [[s]];</script>",
          "<script type=\"text/template\">[[s]]</script>",
          "<script type=\"text/x-handlebars\" t:text=\"s\"></script>",
          "<script t:src=\"s\"></script>",
          "<script t:attr=\"src=s\"></script>",
          "<script t:src=\"'/js/' + s\"></script>",
          "<script t:src=\"@{${s}}\"></script>",
          "<svg><script t:href=\"s\"></script></svg>",
          "<svg><script t:xlink:href=\"s\"></script></svg>",
          "<iframe t:src=\"s\"></iframe>",
          "<frame t:src=\"s\">",
          "<embed t:src=\"s\">",
          "<object t:data=\"s\"></object>",
          "<base t:href=\"s\">",
          "<link t:href=\"s\">",
          "<p t:utext=\"s\">x</p>",
          "<p>[(s)]</p>",
          "<p t:utext=\"'<b>' + s\">x</p>",
          "<p t:utext=\"#{greet(s)}\">x</p>",
          "<script>var a = [(s)];</script>",
          "<script t:utext=\"s\"></script>",
          "<i t:attr=\"a b=s\">x</i>",
          // An HTML textarea/title inside foreign content still ends at the first end tag (#277).
          "<svg><foreignObject><textarea><a title=\"</textarea><script>/*\">*/[[s]]//</a></textarea></foreignObject></svg>",
          "<svg><p></p><title><a title=\"</title><script>/*\">*/[[s]]//</a></title></svg>",
          // The namespace may not depend on rendering (#277).
          "<svg><p t:remove=\"all\"></p><title><script>[[s]]</script></title></svg>",
          "<svg><p t:if=\"s == null\"></p><title><script>[[s]]</script></title></svg>",
          "<math><annotation-xml encoding=\"text/html\" t:attr=\"encoding=s\"><title>[[s]]</title></annotation-xml></math>",
          "<svg><title t:remove=\"tag\"><textarea><script>[[s]]</script></textarea></title></svg>",
          "<svg t:remove=\"tag\"><title><a title=\"</title><script>/*\">*/[[s]]//</a></title></svg>",
          "<math><mi><mglyph t:remove=\"tag\"><textarea><a title=\"</textarea><script>/*\">*/[[s]]//</a></textarea>"
              + "</mglyph></mi></math>",
          // <svg> in <math> is MathML here but real SVG once inserted into HTML as a fragment.
          "<math><svg t:fragment=\"f(String v)\"><title><textarea><a title=\"</textarea><script>/*\">*/[[v]]//</a>"
              + "<a title=\"</script>\"></a></textarea></title></svg></math><div t:insert=\"~{::f(s)}\"></div>",
          "<i t:fragment=\"w(Fragment c)\" t:replace=\"c\">c</i><i t:replace=\"~{::w(~{::svg})}\"><math><svg><title>"
              + "<textarea>[[s]]</textarea></title></svg></math></i>",
          // A nested <svg> that drops its tag is no wrapper once inserted into HTML.
          "<svg><svg t:fragment=\"f(String v)\" t:remove=\"tag\"><textarea><a title=\"</textarea><script>/*\">*/[[v]]//</a>"
              + "<a title=\"</script>\"></a></textarea></svg></svg><div t:insert=\"~{::f(s)}\"></div>",
          "<i t:fragment=\"w(Fragment c)\" t:replace=\"c\">c</i><i t:replace=\"~{::w(~{::g})}\"><svg><g><svg t:remove=\"tag\">"
              + "<textarea>[[s]]</textarea></svg></g></svg></i>",
          // Raw HTML or a non-SVG fragment inside <svg> could close it in a browser, and an HTML
          // <title>/<textarea> fragment would become an SVG one there (#277).
          "<svg><g t:utext=\"html.sanitize(s)\"></g><textarea><a title=\"</textarea><script>/*\">*/[[s]]//</a></textarea></svg>",
          "<svg><g>[(html.sanitize(s))]</g></svg>",
          "<p t:fragment=\"f\">x</p><svg><g t:insert=\"~{::f}\"></g></svg>",
          "<p t:fragment=\"f\">x</p><svg><t:block t:replace=\"~{::f}\"/></svg>",
          "<title t:fragment=\"t\"><script>var a = 1;</script></title><svg><g t:insert=\"~{::t}\"></g></svg>",
          "<i t:fragment=\"w(Fragment c)\"><svg><g t:insert=\"c\"></g></svg></i>",
          "<math><mi><t:block><mglyph><textarea><img src=\"x\" onerror=\"[[s]]\"></textarea></mglyph></t:block></mi></math>",
          // A fragment from inside <svg> would be parsed differently where it is inserted.
          "<svg><g t:fragment=\"g\"><title>t</title></g></svg>",
          "<i t:fragment=\"f(Fragment c)\" t:replace=\"c\">c</i><i t:replace=\"~{::f(~{::g})}\"><svg><g><textarea>[[s]]</textarea></g></svg></i>");

  /** The built-in {@code html.sanitize} turns stored rich text into SafeHtml for raw output. */
  @Test
  void htmlSanitizeFeedsRawOutput() {
    Compilation c =
        Compilation.builder()
            .template(
                "page.html",
                "<div t:params=\"String s\"><section t:utext=\"html.sanitize(s)\">x</section>[(#html.sanitize(s))]</div>")
            .compile()
            .assertSuccess();
    String stored = "<p onclick=\"alert(1)\">See <a href=\"javascript:alert(1)\">the</a> <b>agenda</b>.</p><script>alert(1)</script>";
    String clean = "<p>See <a>the</a> <b>agenda</b>.</p>";
    assertEquals(
        "<div><section>" + clean + "</section>" + clean + "</div>", c.render("page", Map.of("s", stored)));
    assertEquals("<div><section></section></div>", c.render("page", Compilation.model("s", null)));
  }

  @ParameterizedTest
  @FieldSource("REJECTED")
  void unescapableContextsFailToBuild(String markup) {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String s\">\n" + markup + "\n</div>")
            .compile();
    c.assertError("page.html:2:");
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }
}
