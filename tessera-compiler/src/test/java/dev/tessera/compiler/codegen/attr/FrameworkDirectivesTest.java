package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Framework attributes after #584: static directives are the author's code and are written
 * verbatim; a computed value in one is framework-neutral (NeutralValuesTest), and a computed htmx
 * request URL is a same-origin neutral link; a computed class is a plain attribute, written
 * attribute-escaped.
 */
class FrameworkDirectivesTest {

  private static final String HEADER = "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  /** Static directives, shorthand names included, are the author's code and are written verbatim. */
  @Test
  void staticDirectivesPassThroughVerbatim() {
    String markup =
        "<api-entry :security='{\"a\": [1, 2]}' @click.prevent=\"open(id)\" v-bind:x=\"y\""
            + " v-on:submit=\"save\" v-if=\"ok\" v-for=\"i in items\" v-html=\"bio\" #default=\"{ item }\""
            + " .textContent=\"t\" x-data=\"{ open: false }\" x-on:click=\"open = !open\""
            + " hx-on::after-request=\"done()\" hx-vals='js:{n: 1}' ng-click=\"go()\""
            + " data-bind=\"text: name\" _=\"on click toggle .open\" data-script=\"on load log me\""
            + " hx-get=\"/api/x\" hx-post=\"/cdn/y\"></api-entry>";
    Compilation c = compile(markup).assertSuccess();
    assertEquals("<div>" + markup + "</div>", c.render("t", Map.of("s", "x")));
  }

  /**
   * #586: a computed {@code class} is an escaped attribute value like any other, on every path that
   * writes it. The {@code ng}-like class-token stripping of #542 is gone (AngularJS is not supported,
   * guide §5), so the value is written as it is, attribute-escaped; nothing else changes.
   */
  @Test
  void computedClassIsWrittenAsItIs() {
    Compilation c =
        compile("<p t:class=\"s\">1</p><p class=\"a\" t:classappend=\"s\">2</p><p t:attr=\"class=s\">3</p>"
                + "<p t:CLASS=\"s\">4</p>")
            .assertSuccess();
    String value = "x ng-init:alert(document.domain) a.ng-bind DATA_NG-SHOW y";
    assertEquals(
        "<div><p class=\"" + value + "\">1</p><p class=\"a " + value + "\">2</p><p class=\"" + value + "\">3</p>"
            + "<p CLASS=\"" + value + "\">4</p></div>",
        c.render("t", Map.of("s", value)));
    // still attribute-escaped
    assertEquals("<div><p class=\"&quot;&gt;\">1</p><p class=\"a &quot;&gt;\">2</p><p class=\"&quot;&gt;\">3</p>"
        + "<p CLASS=\"&quot;&gt;\">4</p></div>", c.render("t", Map.of("s", "\">")));
  }

  // ------------------------------- htmx request URLs: a computed one is a neutral link

  private static final String[] HTMX_REQUESTS = {
    "data-hx-get", "data-hx-post", "data-hx-put", "data-hx-patch", "data-hx-delete", "DATA-HX-POST",
    "data-sse-connect", "data-ws-connect",
  };

  /** Only a same-origin literal path: no absolute, protocol-relative or page-relative link. */
  @Test
  void htmxRequestUrlRejectsOtherOrigins() {
    for (String name : HTMX_REQUESTS) {
      for (String path : new String[] {"https://evil.example/x", "//evil.example/x", "/\\evil.example/x",
          "javascript:x", "data:text/html,x", "x/y", "~/x"}) {
        compile("<button t:" + name + "=\"@{" + path + "}\">x</button>")
            .assertError("t.html:1:", "t:" + name + " is not allowed", "same-origin path");
      }
    }
    compile("<button t:attr=\"data-hx-get=@{//evil.example}\">x</button>")
        .assertError("t:attr data-hx-get is not allowed", "same-origin path");
    compile("<button t:data-hx-get=\"@{${s}}\">x</button>").assertError("t.html:1:", "dynamic link paths");
  }

  /** URL parsers drop tab, CR and LF, so {@code /<tab>/host} would be protocol-relative: rejected. */
  @Test
  void sameOriginCheckIgnoresTabAndNewlines() {
    for (String path : new String[] {"/\t/evil.example/x", "/\r\n/evil.example", "/\n\\evil.example"}) {
      compile("<button t:data-hx-get=\"@{" + path + "}\">x</button>")
          .assertError("t.html:1:", "link path must not contain whitespace");
      compile("<meta http-equiv=\"refresh\" t:content=\"'0;url=' + @{" + path + "}\">")
          .assertError("t.html:1:", "link path must not contain whitespace");
    }
  }
}
