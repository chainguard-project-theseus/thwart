package dev.chainguard.thwart.compiler.codegen.attr;

import static dev.chainguard.thwart.compiler.codegen.attr.FormFixture.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.codegen.attr.FormFixture.TestForms;
import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.Forms;
import dev.chainguard.thwart.runtime.internal.Escapers;
import dev.chainguard.thwart.runtime.internal.StringOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code <t:csrf-meta/>} writes Spring Security's {@code <meta name="_csrf">} and
 * {@code <meta name="_csrf_header">} from the request's token, and nothing else: no attribute,
 * content, model value or parameter reaches them. A computed {@code content} on a named
 * {@code <meta>} stays a build error, with {@code <t:csrf-meta/>} as the fix-it for the CSRF names.
 */
class CsrfMetaTest {
  private static final String NS = "xmlns:t=\"https://thwart.chainguard.dev\"";
  private static final String TOKEN = "<meta name=\"_csrf\" content=\"tok\" />";
  private static final String HEADER = "<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\" />";
  private static final String NO_ATTRIBUTES = "<t:csrf-meta> takes no attributes";
  private static final String NO_CONTENT = "<t:csrf-meta> takes no content";
  private static final String FIX_IT = "write <t:csrf-meta/> instead";

  private static TestForms forms(Forms.CsrfToken token) {
    TestForms forms = new TestForms();
    forms.token = token;
    return forms;
  }

  private static TestForms withHeader() {
    return forms(new Forms.CsrfToken("_csrf", "tok", "X-CSRF-TOKEN"));
  }

  private static Compilation compile(String params, String body) {
    return Compilation.builder()
        .template("m.html", "<div " + NS + " t:params=\"" + params + "\">" + body + "</div>")
        .compile();
  }

  private static String render(String body, TestForms forms) {
    return compile("", body).assertSuccess().render("m", Map.of(), ctx(forms));
  }

  private static String esc(String value) {
    StringOutput out = new StringOutput();
    Escapers.attr(out, value);
    return out.toString();
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }

  // ---------------------------------------------------------------------------------------------
  // Positive
  // ---------------------------------------------------------------------------------------------

  @Test
  void writesBothTagsInHead() {
    String out = Compilation.builder()
        .template("page.html", "<html " + NS + " t:params=\"\"><head><title>t</title><t:csrf-meta/></head><body>b</body></html>")
        .compile()
        .assertSuccess()
        .render("page", Map.of(), ctx(withHeader()));
    assertEquals("<html><head><title>t</title>" + TOKEN + HEADER + "</head><body>b</body></html>", out);
  }

  @Test
  void emptyElementIsTheSameAsSelfClosing() {
    assertEquals("<div>" + TOKEN + HEADER + "</div>", render("<t:csrf-meta></t:csrf-meta>", withHeader()));
    assertEquals("<div>" + TOKEN + HEADER + "</div>", render("<t:csrf-meta/>", withHeader()));
  }

  @Test
  void selfClosingElementEndsThere() {
    assertEquals("<div>" + TOKEN + HEADER + "<p>x</p></div>", render("<t:csrf-meta/><p>x</p>", withHeader()));
  }

  @Test
  void acceptedInBodyContent() {
    assertEquals("<div><p>" + TOKEN + HEADER + "</p></div>", render("<p><t:csrf-meta/></p>", withHeader()));
    assertEquals("<div><table><tr><td>" + TOKEN + HEADER + "</td></tr></table></div>",
        render("<table><tr><td><t:csrf-meta/></td></tr></table>", withHeader()));
  }

  @Test
  void noTokenWritesNothing() {
    assertEquals("<div></div>", render("<t:csrf-meta/>", new TestForms()));
    assertEquals("<div></div>", compile("", "<t:csrf-meta/>").assertSuccess().render("m", Map.of()));
  }

  @Test
  void tokenWithoutHeaderNameWritesOnlyTheTokenTag() {
    assertEquals("<div>" + TOKEN + "</div>", render("<t:csrf-meta/>", forms(new Forms.CsrfToken("_csrf", "tok"))));
    assertEquals("<div>" + TOKEN + "</div>",
        render("<t:csrf-meta/>", forms(new Forms.CsrfToken("_csrf", "tok", null))));
  }

  @Test
  void conditionalThroughAnEnclosingBlock() {
    Compilation c = compile("boolean b", "<t:block t:if=\"b\"><t:csrf-meta/></t:block>").assertSuccess();
    assertEquals("<div>" + TOKEN + HEADER + "</div>", c.render("m", Map.of("b", true), ctx(withHeader())));
    assertEquals("<div></div>", c.render("m", Map.of("b", false), ctx(withHeader())));
  }

  @Test
  void inALayoutFragmentAndAsAPartial() {
    Compilation c = Compilation.builder()
        .template("layout.html", "<div " + NS + "><t:block t:fragment=\"csrf\"><t:csrf-meta/></t:block></div>")
        .template("page.html", "<html " + NS + " t:params=\"\"><head><t:block t:replace=\"~{layout :: csrf}\"></t:block>"
            + "</head><body>b</body></html>")
        .compile()
        .assertSuccess();
    assertEquals("<html><head>" + TOKEN + HEADER + "</head><body>b</body></html>",
        c.render("page", Map.of(), ctx(withHeader())));
    assertEquals(TOKEN + HEADER, c.render("layout :: csrf", Map.of(), ctx(withHeader())));
    assertEquals("", c.render("layout :: csrf", Map.of(), ctx(new TestForms())));
  }

  @Test
  void formInputAndMetaCarryTheSameToken() {
    String out = render("<form method=\"post\"><button>b</button></form><t:csrf-meta/>", withHeader());
    assertEquals(
        "<div><form method=\"post\"><input type=\"hidden\" name=\"_csrf\" value=\"tok\" /><button>b</button></form>"
            + TOKEN + HEADER + "</div>",
        out);
  }

  /** No parameter or model value reaches the tags, whatever it is called. */
  @Test
  void parametersAndModelValuesDoNotReachTheTags() {
    Map<String, Object> model = new HashMap<>();
    model.put("_csrf", "\"><script>PWN()</script>");
    model.put("csrf", "model-token");
    model.put("csrfToken", "model-token");
    model.put("headerName", "X-Evil");
    String out = compile("String _csrf, String csrf, String csrfToken, String headerName", "<t:csrf-meta/>")
        .assertSuccess()
        .render("m", model, ctx(withHeader()));
    assertEquals("<div>" + TOKEN + HEADER + "</div>", out);
  }

  /** A token or header name with markup or quotes is escaped as an attribute value. */
  @Test
  void hostileTokenAndHeaderAreEscaped() {
    List<String> hostile = List.of("\"><script>PWN()</script>", "' onerror=PWN() x='", "a\" http-equiv=\"refresh",
        "0;url=javascript:PWN()", "{{constructor.constructor('PWN()')()}}", "&amp;\u0000 ", "");
    for (String h : hostile) {
      String out = render("<t:csrf-meta/>", forms(new Forms.CsrfToken("_csrf", h, h)));
      assertEquals("<div><meta name=\"_csrf\" content=\"" + esc(h) + "\" /><meta name=\"_csrf_header\" content=\""
          + esc(h) + "\" /></div>", out, h);
      assertFalse(out.contains("<script"), out);
      assertEquals(8, count(out, "\""), out);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Negative: the element
  // ---------------------------------------------------------------------------------------------

  @Test
  void anyAttributeIsAnError() {
    String[] elements = {
      "<t:csrf-meta t:if=\"true\"/>",
      "<t:csrf-meta t:unless=\"false\"/>",
      "<t:csrf-meta t:each=\"x : ${xs}\"/>",
      "<t:csrf-meta t:content=\"x\"/>",
      "<t:csrf-meta t:name=\"x\"/>",
      "<t:csrf-meta t:fragment=\"m\"/>",
      "<t:csrf-meta t:replace=\"~{this :: p}\"/>",
      "<t:csrf-meta t:remove=\"all\"/>",
      "<t:csrf-meta name=\"_csrf\"/>",
      "<t:csrf-meta name=\"x\"/>",
      "<t:csrf-meta content=\"x\"/>",
      "<t:csrf-meta http-equiv=\"refresh\"/>",
      "<t:csrf-meta charset=\"utf-7\"/>",
      "<t:csrf-meta itemprop=\"x\"/>",
      "<t:csrf-meta property=\"x\"/>",
      "<t:csrf-meta id=\"x\"/>",
      "<t:csrf-meta data-x=\"1\"/>",
      "<t:csrf-meta hidden/>",
    };
    for (String e : elements) {
      compile("String x, java.util.List<String> xs", "<p>p</p>" + e).assertError("m.html:1:", NO_ATTRIBUTES);
    }
  }

  @Test
  void anyContentIsAnError() {
    String[] elements = {
      "<t:csrf-meta>x</t:csrf-meta>",
      "<t:csrf-meta> </t:csrf-meta>",
      "<t:csrf-meta><!-- c --></t:csrf-meta>",
      "<t:csrf-meta><meta name=\"_csrf\" content=\"x\"></t:csrf-meta>",
      "<t:csrf-meta>[[${x}]]</t:csrf-meta>",
      "<t:csrf-meta><t:csrf-meta/></t:csrf-meta>",
    };
    for (String e : elements) {
      compile("String x", e).assertError("m.html:1:", NO_CONTENT);
    }
  }

  /** Where the written {@code <meta>} would not parse as written, the verifier rejects it. */
  @Test
  void placesWhereTheTagsDoNotParseAsWrittenAreRejected() {
    String[] bodies = {
      "<select><t:csrf-meta/></select>",
      "<svg><t:csrf-meta/></svg>",
      "<math><t:csrf-meta/></math>",
      "<noscript><t:csrf-meta/></noscript>",
      "<iframe><t:csrf-meta/></iframe>",
      "<table><t:csrf-meta/></table>",
      "<table><tr><t:csrf-meta/></tr></table>",
    };
    for (String b : bodies) {
      assertFalse(compile("", b).success(), b);
    }
  }

  /** In raw text and RCDATA the element is text: written as is, with no token. */
  @Test
  void inRawTextItIsTextWithoutToken() {
    for (String el : List.of("textarea", "title", "script", "style")) {
      String out = render("<" + el + "><t:csrf-meta/></" + el + ">", withHeader());
      assertFalse(out.contains("tok"), out);
      assertFalse(out.contains("<meta"), out);
    }
  }

  /** The name is exact: another spelling is an unknown element, and no token is written. */
  @Test
  void otherSpellingsWriteNoToken() {
    for (String name : List.of("T:csrf-meta", "t:CSRF-META", "t:csrf_meta", "t:csrfmeta", "t:csrf-meta-x", "csrf-meta")) {
      Compilation c = compile("", "<" + name + "></" + name + ">");
      if (c.success()) {
        String out = c.render("m", Map.of(), ctx(withHeader()));
        assertFalse(out.contains("tok"), name + ": " + out);
        assertFalse(out.contains("<meta"), name + ": " + out);
      }
    }
  }

  /** A fragment selector can't name it: t:block is the one prefixed tag name. */
  @Test
  void cannotBeSelectedAsAFragment() {
    compile("", "<t:csrf-meta/><p t:replace=\"~{this :: t:csrf-meta}\">x</p>")
        .assertError("the only tag name with a prefix that can be selected is t:block");
  }

  // ---------------------------------------------------------------------------------------------
  // Negative: named <meta> content stays forbidden
  // ---------------------------------------------------------------------------------------------

  @Test
  void computedContentOnCsrfNamedMetaPointsToTheElement() {
    for (String name : List.of("_csrf", "_csrf_header", "_CSRF", " _csrf ", "\t_Csrf_Header\n", "csrf-token",
        "csrf-param", "CSRF-Token")) {
      compile("String x", "<meta name=\"" + name + "\" t:content=\"x\"/>")
          .assertError("m.html:", "t:content is not allowed", FIX_IT);
      compile("String x", "<meta name=\"" + name + "\" t:content=\"${x}\"/>").assertError(FIX_IT);
    }
  }

  @Test
  void computedContentOnOtherMetaStaysForbidden() {
    String[] metas = {
      "<meta name=\"htmx-config\" t:content=\"x\"/>",
      "<meta name=\"referrer\" t:content=\"x\"/>",
      "<meta name=\"_csrf_parameter\" t:content=\"x\"/>",
      "<meta name=\"_csrfx\" t:content=\"x\"/>",
      "<meta property=\"_csrf\" t:content=\"x\"/>",
      "<meta itemprop=\"_csrf\" t:content=\"x\"/>",
      "<meta t:content=\"x\"/>",
    };
    for (String m : metas) {
      Compilation c = compile("String x", m);
      c.assertError("m.html:1:", "t:content is not allowed");
      assertTrue(c.errors().stream().noneMatch(e -> e.contains("t:csrf-meta")), m + ": " + c.errors());
    }
  }

  /** A computed name never makes a computed content acceptable. */
  @Test
  void computedNameWithComputedContentIsRejected() {
    Map<String, String> metas = Map.of(
        "<meta t:name=\"x\" t:content=\"x\"/>", "t:content is not allowed",
        "<meta name=\"_csrf\" t:name=\"x\" t:content=\"x\"/>", "t:content is not allowed",
        "<meta t:name=\"'_csrf'\" t:content=\"x\"/>", "t:content is not allowed");
    metas.forEach((m, error) -> compile("String x", m).assertError("m.html:1:", error));
  }

  /** {@code http-equiv} and {@code charset} stay uncomputable, with or without a CSRF name. */
  @Test
  void policyAttributesStayForbidden() {
    compile("String x", "<meta name=\"_csrf\" t:http-equiv=\"x\" content=\"c\"/>")
        .assertError("the http-equiv of a <meta> cannot be computed");
    compile("String x", "<meta name=\"_csrf\" t:charset=\"x\"/>").assertError("sets the encoding");
    compile("String x", "<meta name=\"_csrf\" http-equiv=\"refresh\" t:content=\"x\"/>").assertError("m.html:1:");
  }

  /** Duplicate attributes are a parse error the verifier rejects. */
  @Test
  void duplicateNameIsRejected() {
    assertFalse(compile("String x", "<meta name=\"a\" name=\"_csrf\" t:content=\"x\"/>").success());
    assertFalse(compile("String x", "<meta name=\"_csrf\" name=\"_csrf\" content=\"c\"/>").success());
  }
}
