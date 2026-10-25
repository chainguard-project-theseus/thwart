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
    /** The {@code values} list of an SVG animation (#284): every {@code ;}-separated item is a URL. */
    URL_LIST,
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
          // A computed formaction outside a form that gets no CSRF token must be a link (#314).
          new Line("<form t:action=\"s\"><button t:formaction=\"s\">x</button></form>", Kind.URL, Kind.URL),
          new Line("<img t:src=\"s\"><video t:poster=\"s\"></video><q t:cite=\"s\">q</q>", Kind.URL, Kind.URL, Kind.URL),
          new Line("<svg><a t:xlink:href=\"s\"><text>x</text></a><image t:href=\"s\"/></svg>", Kind.URL, Kind.URL),
          new Line(
              "<svg><a><animate attributeName=\"href\" t:values=\"s\"/><set attributeName=\"href\" t:to=\"s\"/>"
                  + "<animate attributeName=\"href\" t:from=\"s\" t:by=\"s\"/><SET attributeName=\"href\" t:attr=\"to=s\"/></a></svg>",
              Kind.URL_LIST, Kind.URL, Kind.URL, Kind.URL, Kind.URL),
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
              "<form method=\"post\" t:object=\"owner\"><input t:field=\"*{name}\"><span t:errors=\"*{name}\">e</span>"
                  + "<textarea t:field=\"*{name}\"></textarea></form>",
              // The first slot is the injected CSRF hidden-input value, before the fields. A
              // <textarea> is RCDATA: no comments there (#248).
              Kind.HTML, Kind.HTML, Kind.TEXT, Kind.HTML));

  private static Compilation compilation;
  /** {@code '/a;' + s} in an animation's values list: a literal first item (#284). */
  private static Compilation valuesList;
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
    valuesList =
        Compilation.builder()
            .template(
                "list.html",
                "<div t:params=\"String s\"><svg><a><animate attributeName=\"href\" t:values=\"'/a;' + s\"/>"
                    + "<set attributeName=\"href\" t:attr=\"values=|/a;${s}|\"/></a></svg></div>")
            .compile()
            .assertSuccess();
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
            case URL_LIST -> ContextOracle.urlList(slot, s);
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

  private static final Pattern VALUES = Pattern.compile("values=\"([^\"]*)\"");

  /** A benign literal first item must not hide a dangerous later one (#284). */
  static void assertValuesListIsChecked(String s) {
    String out = valuesList.render("list", Map.of("s", s));
    Matcher m = VALUES.matcher(out);
    int slots = 0;
    while (m.find()) {
      slots++;
      String problem = ContextOracle.urlList(m.group(1), "/a;" + s);
      if (problem != null) {
        fail("values slot " + slots + " for " + show(s) + ": " + problem + ": " + m.group(1) + "\n" + out);
      }
    }
    assertEquals(2, slots, out);
  }

  @ParameterizedTest
  @FieldSource("dev.tessera.compiler.security.HostileStrings#VECTORS")
  void vectorCannotHideInAValuesList(String vector) {
    assertValuesListIsChecked(vector);
  }

  @Test
  void randomStringsCannotHideInAValuesList() {
    HostileStrings.forEachSeeded(2000, (rnd, i) -> assertValuesListIsChecked(HostileStrings.random(rnd, 12)));
  }

  @Test
  void dangerousUrlsAreReplacedEverywhere() {
    String out = render("javascript:alert(1)");
    assertTrue(out.contains("href=\"about:invalid#tessera\""), out);
    assertTrue(out.contains("values=\"about:invalid#tessera\""), out);
    assertTrue(out.contains(" to=\"about:invalid#tessera\""), out);
    assertTrue(!Pattern.compile("[ :](href|values|to|from|by|action|formaction|src|poster|cite)=\"javascript", Pattern.CASE_INSENSITIVE).matcher(out).find(), out);
    // A dangerous later item of an animation's values list (#284), alone and after a literal one.
    String list = render("https://a;javascript:alert(1)");
    assertTrue(list.contains("values=\"about:invalid#tessera\""), list);
    assertTrue(!Pattern.compile("values=\"[^\"]*javascript", Pattern.CASE_INSENSITIVE).matcher(list).find(), list);
    String later = valuesList.render("list", Map.of("s", "javascript:alert(1)"));
    assertEquals(2, later.split("values=\"about:invalid#tessera\"", -1).length - 1, later);
  }

  /**
   * Negative (#285): a hostile computed action never receives the CSRF token. Whenever the token
   * is written, the action as a browser reads it has no scheme and does not start with two
   * slashes (either way round), so the form posts to the page's origin.
   */
  @Test
  void csrfTokenNeverPostsCrossOrigin() {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"String s\"><form method=\"post\" t:action=\"s\"></form></div>")
            .compile()
            .assertSuccess();
    Pattern action = Pattern.compile("action=\"([^\"]*)\"");
    java.util.function.Consumer<String> check =
        s -> {
          String out = c.render("page", Map.of("s", s), hostileContext("tok"));
          if (!out.contains("_csrf")) {
            return;
          }
          Matcher m = action.matcher(out);
          String url = m.find() ? ContextOracle.decodeHtml(m.group(1)) : "";
          String scheme = ContextOracle.browserScheme(url);
          String path = url.replaceFirst("^[\\x00-\\x20]+", "").replaceAll("[\t\n\r]", "");
          boolean protocolRelative =
              path.length() >= 2 && "/\\".indexOf(path.charAt(0)) >= 0 && "/\\".indexOf(path.charAt(1)) >= 0;
          if (scheme != null || protocolRelative) {
            fail("token posted cross-origin for " + show(s) + ": " + out);
          }
        };
    for (String v : HostileStrings.VECTORS) {
      check.accept(v);
    }
    for (String v : List.of("https://evil/", "//evil/", "/\\evil/", "\\\\evil", "\t//evil", "\u0000//evil")) {
      check.accept(v);
      assertTrue(!c.render("page", Map.of("s", v), hostileContext("tok")).contains("_csrf"), show(v));
    }
    HostileStrings.forEachSeeded(2000, (rnd, i) -> check.accept(HostileStrings.random(rnd, 12)));
    assertTrue(c.render("page", Map.of("s", "/x"), hostileContext("tok")).contains("_csrf"));
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
          "<title t:remove=\"tag\"><script>[[s]]</script></title>",
          "<textarea t:remove=\"tag\"><script>[[s]]</script></textarea>",
          "<script t:remove=\"tag\"><img src=x onerror=[[s]]></script>",
          "<svg t:remove=\"tag\"><text><![CDATA[ x><script>/*]]>*/[[s]]<!--</script>--></text></svg>",
          "<i t:attr=\"a b=s\">x</i>",
          "<!--> <script> -->\n[[s]]\n<!-- </script> -->",
          "<!---> <script> -->\n[[s]]\n<!-- </script> -->",
          "<!-- x --!> <script> -->\n[[s]]\n<!-- </script> -->",
          "<p><![CDATA[ x><script>[[s]]</script> ]]></p>",
          "<p><![CDATA[ [[s]] ]]></p>",
          "<?x><script>/* ?>*/\n[[s]]\n<!-- </script> -->",
          "<!DOCTYPE html PUBLIC \"><script>/*\" \"x\">*/\n[[s]]\n<!-- </script> -->",
          "</ <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<? <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<![if <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<!-x <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<!/ <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<!? <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<!! <!-- > <script> -->\n[[s]]\n<!-- </script> -->",
          "<p><[[s]]></p>",
          "<p><[[s]]script>alert(1)<[[s]]/script></p>",
          "<p></[[s]]></p>",
          "<textarea></[[s]]></textarea>",
          "<textarea></text[[s]]area></textarea>",
          "<title><[[s]]></title>",
          "<script>var x = 2 <!-[[s]]</script>",
          // #313: a browser reads \ as /, so these load from another origin
          "<script t:src=\"@{/\\evil.example/a.js}\"></script>",
          "<base t:href=\"@{/\\evil.example/}\">",
          // A browser reads these as raw text up to the first </name, which must be the end tag Tessera
          // reads, and which HTML known only at runtime could contain.
          "<xmp><i title=\"</xmp><script>/*\"></i></xmp>*/\n[[s]]\n<!-- </script> -->",
          "<noscript><!-- </noscript><script> --></noscript>\n[[s]]\n<!-- </script> -->",
          "<textarea/><!-- </textarea><script> -->\n[[s]]\n<!-- </script> -->",
          "<plaintext></plaintext>",
          "<noscript t:utext=\"html.sanitize(s)\"></noscript>",
          // A nested title/textarea can hide the end tag a browser uses, so the RCDATA-escaped value
          // would sit in a live <script>.
          "<xmp><textarea></xmp><script>var a = [[s]];</script></textarea></xmp>",
          "<noscript><title></noscript><script>var a = [[s]];</script></title></noscript>",
          "<iframe><textarea></iframe><script>var a = [[s]];</script></textarea></iframe>",
          "<noembed><title></noembed><script>var a = [[s]];</script></title></noembed>",
          "<noframes><title></noframes><script>var a = [[s]];</script></title></noframes>",
          "<xmp><p title=\"</xmp><script>/*\">*/[[s]]//</p></xmp>",
          "<iframe/><title></iframe><script>var a = [[s]];</script></title>",
          "<plaintext>[[s]]</plaintext>",
          // and markup inserted at render time could hold such an end tag
          "<noscript><p t:utext=\"html.sanitize(s)\">x</p></noscript>",
          "<xmp t:utext=\"html.sanitize(s)\"></xmp>",
          "<noembed>[(#html.sanitize(s))]</noembed>",
          "<noframes><textarea>[(#html.sanitize(s))]</textarea></noframes>",
          // a browser ends these tags at the first '>' and runs [[s]] as script
          "<p title=a\"><script>//\">\n[[s]]\n//</p><!-- </script> -->",
          "<p title=\"a\"b\"><script>//\">\n[[s]]\n//</p><!-- </script> -->",
          "<p></p \"><script>//\">\n[[s]]\n<!-- </script> -->",
          // a browser does not end these tag names at U+000B, so <textarea>/<title> is an unknown element
          "<textarea\u000b><a onclick=\"[[s]]\">x</a></textarea>",
          "<title\u000b><img src=x onerror=\"[[s]]\"></title>",
          // a browser does not end this tag at '/>', so the <script> after it is live
          "<textarea x=y/><a title=\"</textarea><script>//\">\n[[s]]\n//</a><!-- </script> -->",
          // a browser reads a valueless type first, so this is a classic script, not JSON
          "<script type/ type=\"application/json\">[[s]]</script>",
          // #314: a submit button sends the form's CSRF token to its formaction
          "<form method=\"post\"><button t:formaction=\"s\">x</button></form>",
          "<form method=\"post\"><button t:attr=\"formaction=@{//evil.example/}\">x</button></form>",
          "<form method=\"post\"><input type=\"submit\" formaction=\"https://evil.example/\"></form>",
          // #348: a browser moves the text in front of the table and keeps the comments that break {{
          "<table>[[s]]<tr><td>x</td></tr></table>",
          "<table><tr><td>x</td></tr><tr t:text=\"s\"></tr></table>",
          "<table><tbody>[[s]]</tbody><thead>[[s]]</thead><tfoot>[[s]]</tfoot></table>",
          "<table><t:block>[[s]]</t:block></table>",
          "<table><tr t:utext=\"html.sanitize(s)\"></tr></table>",
          "<table><tr>[(#html.sanitize(s))]</tr></table>",
          // #348: the <tr> ends the cell, so the text after it is read in the table, as above
          "<table><tr><td><p><tr></tr>[[s]]</p></td></tr></table>",
          "<table><caption><col>[[s]]</caption></table>",
          "<table><form>[[s]]</form></table>");

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

  /**
   * #283: a fragment can carry a literal {@code </noscript} that ends the caller's
   * {@code <noscript>} in a browser, which neither template's parse sees, so markup is not
   * inserted into these elements.
   */
  @Test
  void fragmentsAreNotInsertedIntoBrowserRawElements() {
    String frag = "<div t:fragment=\"f(String x)\"><textarea></noscript><script>var a = [[x]];</script></textarea></div>";
    String message = " inside <noscript> is not supported: a browser reads the content of <noscript> as text up to"
        + " the first '</noscript', and inserted markup could contain one; move it outside the element";
    String[][] rejected = {
      {"<noscript><div t:replace=\"~{frag :: f(x)}\"></div></noscript>", "page.html:2:16: t:replace"},
      {"<noscript t:insert=\"~{frag :: f(x)}\"></noscript>", "page.html:2:11: t:insert"},
      {"<NoScript><p><b t:insert=\"~{frag :: f(x)}\"></b></p></NoScript>", "page.html:2:17: t:insert"},
    };
    for (String[] r : rejected) {
      Compilation c =
          Compilation.builder()
              .template("frag.html", frag)
              .template("page.html", "<div t:params=\"String x\">\n" + r[0] + "\n</div>")
              .compile();
      String[] at = r[1].split(": ");
      c.assertError(at[0], at[1] + message.replace("noscript", r[0].startsWith("<NoScript") ? "NoScript" : "noscript"));
      assertNull(c.generatedSource("tessera.generated.PageTemplate"), r[0]);
    }
    // t:replace on the element itself puts the fragment outside it; [[...]] and t:text are escaped.
    Compilation.builder()
        .template("frag.html", "<p t:fragment=\"f(String x)\">[[x]]</p>")
        .template(
            "page.html",
            "<div t:params=\"String x\"><noscript t:replace=\"~{frag :: f(x)}\"></noscript>"
                + "<noscript><p>[[x]]</p><p t:text=\"x\">y</p></noscript><div t:insert=\"~{frag :: f(x)}\"></div></div>")
        .compile()
        .assertSuccess();
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
