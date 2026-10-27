package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vue, Alpine, htmx, _hyperscript, AngularJS and Knockout attributes whose values the framework
 * runs as JavaScript (#235, #249): an expression there is a build error, like {@code t:onclick}; a
 * static value, shorthand names included, is written verbatim. htmx request URLs take only a
 * same-origin link (#249).
 */
class FrameworkDirectivesTest {

  private static final String HEADER = "<div xmlns:t=\"https://tessera.dev\" t:params=\"String s\">";

  private static Compilation compile(String body) {
    return Compilation.builder().template("t.html", HEADER + body + "</div>").compile();
  }

  /** One attribute per expression-evaluating class; each is a build error with any value. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // Vue
        "v-on:click", "@click", "@click.prevent", "v-bind", "x-bind", ":href", "v-bind:title.prop",
        "v-model", "v-if", "v-else-if", "v-show", "v-for", "v-html", "V-HTML", "v-text", "v-slot:item",
        "#default", ".textContent", "v-focus",
        // Alpine
        "x-data", "x-init", "x-on:click", "x-bind:style", "x-html", "x-text", "x-show", "x-effect",
        // htmx
        "hx-on", "hx-on:click", "hx-on::after-request", "hx-on-click", "hx-on--after-request",
        "data-hx-on:click", "hx-vals", "data-hx-vals", "hx-vars", "hx-headers", "hx-request",
        "hx-trigger",
        // _hyperscript
        "_", "script", "data-script",
        // AngularJS
        "ng-click", "ng-init", "ng-bind-html", "data-ng-click", "x-ng-click", "ng:click", "ng_click",
        // Knockout
        "data-bind",
      })
  void dynamicValueIsABuildError(String name) {
    compile("<p t:" + name + "=\"s\">x</p>")
        .assertError("t.html:1:", "t:" + name + " is not allowed", "run as JavaScript", "static attribute");
    compile("<p t:attr=\"" + name + "=s\">x</p>").assertError("t.html:1:");
  }

  /** Knockout evaluates {@code params} on a component (custom element) as a binding. */
  @Test
  void knockoutComponentParamsIsABuildError() {
    // t:params declares template parameters, so t:attr is the only way to compute it.
    compile("<my-widget t:attr=\"params=s\"></my-widget>")
        .assertError("t.html:1:", "t:attr params is not allowed", "run as JavaScript");
    compile("<my-widget params=\"name: n\"></my-widget>").assertSuccess();
  }

  /** Even a string literal is rejected, as for t:onclick: the author writes the static attribute. */
  @Test
  void literalExpressionIsABuildError() {
    compile("<p t:v-on:click=\"'go()'\">x</p>").assertError("t:v-on:click is not allowed");
    compile("<p t:v-html=\"'<b>x</b>'\">x</p>").assertError("t:v-html is not allowed");
    compile("<p t:x-data=\"'{}'\">x</p>").assertError("t:x-data is not allowed");
  }

  /**
   * The issue's reproduction: a hostile string never ends up in a directive as code. v-on is
   * rejected; v-bind:x takes the string as a JSON string literal (#246, see JsonBindingTest).
   */
  @Test
  void issueReproductionIsRejected() {
    compile("<api-entry t:v-on:click=\"s\" t:v-bind:x=\"s\"></api-entry>").assertError("t:v-on:click is not allowed");
    Compilation c = compile("<api-entry t:v-bind:x=\"s\"></api-entry>").assertSuccess();
    assertEquals(
        "<div><api-entry v-bind:x=\"&quot;\\&quot;);alert(1)\\/\\/&quot;\"></api-entry></div>",
        c.render("t", Map.of("s", "\");alert(1)//")));
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
            + " hx-get=\"https://api.example/x\" hx-post=\"//cdn.example/y\"></api-entry>";
    Compilation c = compile(markup).assertSuccess();
    assertEquals("<div>" + markup + "</div>", c.render("t", Map.of("s", "x")));
  }

  /** Ordinary data attributes and htmx string attributes still take expressions, escaped. */
  @Test
  void dataAttributesStayOrdinary() {
    Compilation c =
        compile("<p t:data-security=\"s\" t:data-hx-target=\"s\" t:data-action=\"s\">x</p>").assertSuccess();
    assertEquals(
        "<div><p data-security=\"&quot;);alert(1)//\" data-hx-target=\"&quot;);alert(1)//\""
            + " data-action=\"&quot;);alert(1)//\">x</p></div>",
        c.render("t", Map.of("s", "\");alert(1)//")));
  }

  // ------------------------------------------------------------- htmx request URLs (#249)

  private static final String[] HTMX_REQUESTS = {
    "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete", "data-hx-get", "data-hx-delete", "HX-POST",
    "sse-connect", "ws-connect", "data-ws-connect",
  };

  /** htmx swaps the response in as HTML, so a computed URL that is not a link is a build error. */
  @Test
  void htmxRequestUrlRejectsNonLinks() {
    for (String name : HTMX_REQUESTS) {
      compile("<button t:" + name + "=\"s\">x</button>")
          .assertError("t.html:1:", "t:" + name + " is not allowed", "swaps the response", "@{/items/");
      compile("<button t:attr=\"" + name + "=s\">x</button>").assertError("t.html:1:", "t:attr " + name);
      compile("<button t:" + name + "=\"'/x/' + s\">x</button>").assertError("t:" + name + " is not allowed");
      compile("<button t:" + name + "=\"|/x/${s}|\">x</button>").assertError("t:" + name + " is not allowed");
      compile("<button t:" + name + "=\"'/x'\">x</button>").assertError("t:" + name + " is not allowed");
      compile("<button t:" + name + "=\"@{/x} + s\">x</button>").assertError("t:" + name + " is not allowed");
      compile("<button t:" + name + "=\"${s} ?: @{/x}\">x</button>").assertError("t:" + name + " is not allowed");
    }
  }

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
    compile("<button t:attr=\"hx-get=@{//evil.example}\">x</button>")
        .assertError("t:attr hx-get is not allowed", "same-origin path");
    compile("<button t:hx-get=\"@{${s}}\">x</button>").assertError("t.html:1:", "dynamic link paths");
  }

  /** URL parsers drop tab, CR and LF, so {@code /<tab>/host} would be protocol-relative: rejected. */
  @Test
  void sameOriginCheckIgnoresTabAndNewlines() {
    for (String path : new String[] {"/\t/evil.example/x", "/\r\n/evil.example", "/\n\\evil.example"}) {
      compile("<button t:hx-get=\"@{" + path + "}\">x</button>")
          .assertError("t.html:1:", "link path must not contain whitespace");
      compile("<meta http-equiv=\"refresh\" t:content=\"'0;url=' + @{" + path + "}\">")
          .assertError("t.html:1:", "link path must not contain whitespace");
    }
  }

  /** htmx 1.x hx-sse / hx-ws hold connect:<url>: no expression at all; static values pass. */
  @Test
  void legacyHtmxConnectRejectsExpressions() {
    for (String name : new String[] {"hx-sse", "hx-ws", "data-hx-sse", "HX-WS"}) {
      compile("<div t:" + name + "=\"'connect:' + @{/events}\">x</div>")
          .assertError("t.html:1:", "t:" + name + " is not allowed", "connect:<url>", "sse-connect");
      compile("<div t:attr=\"" + name + "=${s}\">x</div>").assertError("t:attr " + name + " is not allowed");
    }
    Compilation c = compile("<div hx-sse=\"connect:/events\" hx-ws=\"connect:/chat\">x</div>").assertSuccess();
    assertEquals(
        "<div><div hx-sse=\"connect:/events\" hx-ws=\"connect:/chat\">x</div></div>",
        c.render("t", Map.of("s", "x")));
  }

  @Test
  void htmxRequestUrlAcceptsASameOriginLink() {
    Compilation c =
        compile("<button t:hx-get=\"@{/items/{id}(id=${s})}\" t:attr=\"data-hx-post=@{/save(q=${s})}\""
                + " hx-target=\"#out\">x</button>")
            .assertSuccess();
    assertEquals(
        "<div><button hx-target=\"#out\" data-hx-post=\"/save?q=//evil.example%3F\""
            + " hx-get=\"/items/%2F%2Fevil.example%3F\">x</button></div>",
        c.render("t", Map.of("s", "//evil.example?")));
  }

  /** hx-push-url and hx-replace-url only change the address bar: an ordinary URL attribute. */
  @Test
  void htmxHistoryUrlsAreUrls() {
    Compilation c = compile("<a t:hx-push-url=\"s\" t:hx-replace-url=\"s\">x</a>").assertSuccess();
    assertEquals(
        "<div><a hx-push-url=\"/x?a=1&amp;b=2\" hx-replace-url=\"/x?a=1&amp;b=2\">x</a></div>",
        c.render("t", Map.of("s", "/x?a=1&b=2")));
    assertEquals(
        "<div><a hx-push-url=\"about:invalid#tessera\" hx-replace-url=\"about:invalid#tessera\">x</a></div>",
        c.render("t", Map.of("s", "javascript:alert(1)")));
  }

  /**
   * The selector and swap attributes next to a request URL stay ordinary, escaped attributes as
   * {@code data-hx-*}. Without {@code data-} they are not in the framework table, so they are
   * unknown names (#330), with a fix-it.
   */
  @Test
  void htmxTargetAndSwapStayOrdinary() {
    Compilation c =
        compile("<p t:data-hx-target=\"s\" t:data-hx-swap=\"s\" t:data-hx-select=\"s\">x</p>").assertSuccess();
    assertEquals(
        "<div><p data-hx-target=\"&quot;&gt;\" data-hx-swap=\"&quot;&gt;\" data-hx-select=\"&quot;&gt;\">x</p></div>",
        c.render("t", Map.of("s", "\">")));
    compile("<p t:hx-target=\"s\">x</p>")
        .assertError("t.html:1:", "t:hx-target is not allowed", "not a known attribute", "use data-hx-target");
  }
}
