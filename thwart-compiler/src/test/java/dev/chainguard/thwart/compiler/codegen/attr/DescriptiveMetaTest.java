package dev.chainguard.thwart.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.security.HostileStrings;
import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A computed {@code content} on a descriptive {@code <meta>} (docs/context-model.md §6.1): exactly
 * one static {@code name} or {@code property} from a fixed list of keys whose content is display
 * text or one URL for search results and link previews. Text keys are ATTRIBUTE ({@code Escapers.attr}),
 * URL keys URL ({@code Escapers.url}). Every other {@code <meta>} content stays a build error, with
 * the fix-it for its case.
 */
class DescriptiveMetaTest {

  private static final String HEADER =
      "<div xmlns:t=\"https://thwart.chainguard.dev\" t:params=\"String s, boolean b, int n,"
          + " java.util.List<String> xs, com.example.Page p\">";

  private static final String PAGE = "package com.example; public record Page(String title) {}";

  static final List<String> TEXT_KEYS = List.of("description", "keywords", "author", "og:title", "og:description",
      "og:site_name", "og:image:alt", "twitter:title", "twitter:description", "twitter:image:alt");
  static final List<String> URL_KEYS = List.of("og:image", "og:url", "twitter:image");

  private static Compilation compile(String body) {
    return Compilation.builder().java("com.example.Page", PAGE).messages("d=A page about {0}\n")
        .template("t.html", HEADER + body + "</div>").compile();
  }

  private static String render(String body, Object... kv) {
    Map<String, Object> m = new HashMap<>(Map.of("b", false, "n", 0));
    m.putAll(Compilation.model(kv));
    String out = compile(body).assertSuccess().render("t", m);
    return out.substring("<div>".length(), out.length() - "</div>".length());
  }

  // ---------------------------------------------------------------------------------------------
  // Positive
  // ---------------------------------------------------------------------------------------------

  @Test
  void textKeysTakeAnyValueEscapedUnderNameAndProperty() {
    for (String key : TEXT_KEYS) {
      for (String keyAttr : List.of("name", "property")) {
        String body = "<meta " + keyAttr + "=\"" + key + "\" t:content=\"s\">";
        Compilation c = compile(body).assertSuccess();
        for (String h : HostileStrings.VECTORS) {
          String out = c.render("t", Compilation.model("s", h, "b", false, "n", 0));
          String meta = out.substring("<div>".length(), out.length() - "</div>".length());
          assertTrue(meta.startsWith("<meta " + keyAttr + "=\"" + key + "\" content=\"") && meta.endsWith("\">"),
              key + " " + h + ": " + meta);
          String value = meta.substring(("<meta " + keyAttr + "=\"" + key + "\" content=\"").length(), meta.length() - 2);
          assertTrue(value.indexOf('"') < 0 && value.indexOf('<') < 0 && value.indexOf('>') < 0, key + ": " + meta);
        }
      }
    }
    assertEquals("<meta name=\"description\" content=\"&quot;&gt;&lt;script&gt;x&lt;/script&gt;\">",
        render("<meta name=\"description\" t:content=\"s\">", "s", "\"><script>x</script>"));
  }

  @Test
  void keyIsFoldedAToZ() {
    assertEquals("<meta NAME=\"Description\" content=\"d\">",
        render("<meta NAME=\"Description\" t:content=\"s\">", "s", "d"));
    assertEquals("<meta property=\"OG:Title\" content=\"d\">",
        render("<meta property=\"OG:Title\" t:content=\"s\">", "s", "d"));
    assertEquals("<meta name=\"TWITTER:IMAGE\" content=\"https://cdn.example/x.png\">",
        render("<meta name=\"TWITTER:IMAGE\" t:content=\"s\">", "s", "https://cdn.example/x.png"));
  }

  @Test
  void computedContentReplacesAStaticOne() {
    assertEquals("<meta name=\"description\" content=\"d\">",
        render("<meta name=\"description\" content=\"static\" t:content=\"s\">", "s", "d"));
  }

  @Test
  void nullOmitsTheContent() {
    assertEquals("<meta name=\"description\">", render("<meta name=\"description\" t:content=\"s\">", "s", null));
    assertEquals("<meta property=\"og:url\">", render("<meta property=\"og:url\" t:content=\"s\">", "s", null));
  }

  @Test
  void worksUnderConditionsAndLoops() {
    assertEquals("<meta property=\"og:image\" content=\"https://cdn.example/a.png\">"
            + "<meta property=\"og:image\" content=\"https://cdn.example/b.png\">",
        render("<meta t:each=\"x : ${xs}\" property=\"og:image\" t:content=\"${x}\">", "xs",
            List.of("https://cdn.example/a.png", "https://cdn.example/b.png")));
    assertEquals("<meta name=\"description\" content=\"no\">",
        render("<meta t:if=\"b\" name=\"description\" t:content=\"'yes'\">"
            + "<meta t:unless=\"b\" name=\"description\" t:content=\"'no'\">"));
    assertEquals("<meta name=\"description\" content=\"Talks of 2027\">",
        render("<meta name=\"description\" t:content=\"|Talks of ${n}|\">", "n", 2027));
    compile("<meta property=\"og:title\" t:content=\"${p.title}\">").assertSuccess();
  }

  /** The value types ATTRIBUTE takes: a whole record is not text. */
  @Test
  void wholeRecordIsRejected() {
    compile("\n<meta property=\"og:title\" t:content=\"${p}\">").assertError("t.html:2:");
  }

  @Test
  void messageIsAllowedOnATextKey() {
    compile("<meta name=\"description\" t:content=\"#{d(${s})}\">").assertSuccess();
    compile("<meta property=\"og:description\" t:content=\"#{d(${s})}\">").assertSuccess();
  }

  @Test
  void urlKeysAreSchemeChecked() {
    for (String key : URL_KEYS) {
      String body = "<meta property=\"" + key + "\" t:content=\"s\">";
      String open = "<meta property=\"" + key + "\" content=\"";
      assertEquals(open + "https://cdn.example/x.png\">", render(body, "s", "https://cdn.example/x.png"));
      assertEquals(open + "http://example.com/a?b=1&amp;c=2\">", render(body, "s", "http://example.com/a?b=1&c=2"));
      assertEquals(open + "/x.png\">", render(body, "s", "/x.png"));
      for (String bad : List.of("javascript:PWN()", " JaVaScRiPt:PWN()", "java\tscript:PWN()",
          "data:text/html,<script>PWN()</script>", "vbscript:msgbox(1)")) {
        assertEquals(open + "about:invalid#thwart\">", render(body, "s", bad), key + " " + bad);
      }
    }
    assertEquals("<meta name=\"twitter:image\" content=\"/img/7.png\">",
        render("<meta name=\"twitter:image\" t:content=\"@{/img/{id}.png(id=${n})}\">", "n", 7));
  }

  // ---------------------------------------------------------------------------------------------
  // Negative
  // ---------------------------------------------------------------------------------------------

  private static final String NOT_ON_LIST = "is on Thwart's descriptive list";

  @ParameterizedTest
  @ValueSource(strings = {
    "referrer", "viewport", "color-scheme", "theme-color", "robots", "googlebot", "htmx-config",
    "turbo-visit-control", "google-site-verification", "facebook-domain-verification", "fb:app_id", "fb:admins",
    "twitter:player", "twitter:card", "twitter:site", "og:video", "og:audio", "og:type", "og:image:secure_url",
    "apple-itunes-app", "application-name", "fediverse:creator", "_csrf_parameter", "x-unknown", "",
  })
  void otherKeysStayForbidden(String key) {
    for (String keyAttr : List.of("name", "property")) {
      compile("\n<meta " + keyAttr + "=\"" + key + "\" t:content=\"s\">")
          .assertError("t.html:2:", "t:content is not allowed", "a computed content is allowed only on a <meta> whose"
              + " static name or property " + NOT_ON_LIST, "'" + key + "' can be read as policy or configuration",
              "one <meta> per case under t:if");
    }
  }

  @Test
  void literalsUnderOtherKeysStayForbidden() {
    compile("<meta name=\"referrer\" t:content=\"b ? 'no-referrer' : 'origin'\">")
        .assertError("t:content is not allowed", NOT_ON_LIST);
    compile("<meta name=\"viewport\" t:content=\"'width=device-width'\">").assertError("t:content is not allowed");
  }

  @Test
  void keyIsNotTrimmedOrUnicodeFolded() {
    for (String key : List.of(" description", "description ", "\tdescription", "description ",
        " og:url ", " Author ")) {
      compile("\n<meta name=\"" + key + "\" t:content=\"s\">")
          .assertError("t.html:2:", "t:content is not allowed", "only without the surrounding whitespace");
    }
    for (String key : List.of("descrİption", "descr&#x130;ption", "og:tıtle", "Keywords", "desc ription")) {
      compile("\n<meta name=\"" + key + "\" t:content=\"s\">").assertError("t.html:2:", "t:content is not allowed",
          NOT_ON_LIST);
    }
  }

  @Test
  void csrfNamesPointToTheElement() {
    for (String key : List.of("_csrf", "_csrf_header", "csrf-token", "csrf-param", "CSRF-Token")) {
      compile("\n<meta name=\"" + key + "\" t:content=\"s\">")
          .assertError("t.html:2:", "t:content is not allowed", "write <t:csrf-meta/> instead");
    }
  }

  @Test
  void bothNameAndPropertyIsRejected() {
    compile("\n<meta name=\"description\" property=\"og:description\" t:content=\"s\">")
        .assertError("t.html:2:", "t:content is not allowed", "both a name and a property", "keep one");
    compile("<meta property=\"og:title\" name=\"og:title\" t:content=\"s\">").assertError("both a name and a property");
  }

  @Test
  void otherMeaningAttributesAreRejected() {
    compile("<meta name=\"description\" http-equiv=\"refresh\" t:content=\"s\">")
        .assertError("t:content is not allowed", "refresh");
    compile("<meta name=\"description\" http-equiv=\"content-language\" t:content=\"s\">")
        .assertError("t:content is not allowed", "pragma the browser acts on");
    compile("<meta property=\"og:url\" http-equiv=\"Content-Security-Policy\" t:content=\"s\">")
        .assertError("t:content is not allowed", "pragma the browser acts on");
    for (String pragma : List.of("content-security-policy", "default-style", "set-cookie", "x-dns-prefetch-control")) {
      compile("<meta http-equiv=\"" + pragma + "\" t:content=\"s\">")
          .assertError("t:content is not allowed", "pragma the browser acts on");
    }
    compile("<meta name=\"description\" http-equiv=\"Content-Type\" t:content=\"s\">")
        .assertError("t:content is not allowed", "encoding");
    compile("<meta name=\"description\" charset=\"utf-8\" t:content=\"s\">")
        .assertError("t:content is not allowed", "<meta charset>");
    compile("<meta name=\"description\" itemprop=\"description\" t:content=\"s\">")
        .assertError("t:content is not allowed", "microdata", "application/ld+json");
    compile("<meta itemprop=\"description\" t:content=\"s\">")
        .assertError("t:content is not allowed", "microdata", "application/ld+json");
  }

  @Test
  void noKeyIsRejected() {
    compile("\n<meta t:content=\"s\">").assertError("t.html:2:", "t:content is not allowed",
        "needs a static name or property");
    compile("<meta name t:content=\"s\">").assertError("t:content is not allowed", NOT_ON_LIST);
  }

  /** Fail closed: a computed key, even beside a static descriptive one, never admits a computed content. */
  @Test
  void computedKeyIsRejected() {
    for (String key : List.of("t:name=\"'description'\"", "t:property=\"'og:title'\"", "t:itemprop=\"s\"",
        "t:http-equiv=\"s\"", "t:charset=\"s\"")) {
      for (String stat : List.of("", "name=\"description\" ", "property=\"og:title\" ")) {
        for (String meta : List.of("<meta " + stat + key + " t:content=\"s\">", "<meta " + key + " " + stat
            + "t:content=\"s\">", "<meta t:content=\"s\" " + stat + key + ">")) {
          compile("\n" + meta).assertError("t.html:2:", "t:content is not allowed",
              "a computed content needs a static name or property", "makes that depend on data");
        }
      }
    }
    compile("<meta name=\"description\" t:name=\"'description'\" t:content=\"s\">")
        .assertError("t:name is not allowed")
        .assertError("t:content is not allowed");
  }

  @Test
  void contentIsNotComputedOnOtherElements() {
    for (String body : List.of("<p name=\"description\" t:content=\"s\">x</p>", "<link name=\"description\" t:content=\"s\">",
        "<div property=\"og:title\" t:content=\"s\"></div>")) {
      compile(body).assertError("t:content is not allowed", "is not on Thwart's list of computed attributes");
    }
  }

  @Test
  void metaInSvgIsForeign() {
    compile("<svg><meta name=\"description\" t:content=\"s\"></meta></svg>")
        .assertError("t:content is not allowed", "SVG and MathML");
  }
}
