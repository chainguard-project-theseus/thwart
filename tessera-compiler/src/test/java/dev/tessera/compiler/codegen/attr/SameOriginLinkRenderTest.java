package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.codegen.attr.FormFixture.TestForms;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Links;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The compiler checks that the literal path of a link in a same-origin slot starts with a single
 * {@code /}, but the URL is built at render time by {@link Links} (#358): a context path ending in
 * {@code /}, a custom {@code Links} context path or encode hook, or an empty path variable could turn {@code /a} into the
 * protocol-relative {@code //a}. Every slot that uses {@code LinkCompiler.isSameOriginPath}
 * re-checks the built URL and fails the render rather than leave the origin, as does a resource URL
 * with a rooted path (#756).
 */
class SameOriginLinkRenderTest {
  /** One template per checked slot; each links to {@code @{/a}} or {@code @{/{x}/a(x=${u})}}. */
  private static final String[] SLOTS = {
    "<button t:data-hx-get=\"@{/a}\">x</button>",
    "<button t:attr=\"data-hx-post=@{/{x}/a(x=${u})}\">x</button>",
    "<meta http-equiv=\"refresh\" t:content=\"'3;url=' + @{/a}\">",
    "<base t:href=\"@{/a}\">",
    "<base t:attr=\"href=@{/{x}/a(x=${u})}\">",
    "<form method=\"post\" t:action=\"@{/a}\"></form>",
    "<form action=\"/f\"><button t:formaction=\"@{/a}\">Go</button></form>",
    "<form method=\"post\" action=\"/transfer\"><button t:formaction=\"@{/a}\">Go</button></form>",
    "<form method=\"post\" action=\"/transfer\"><button t:formaction=\"@{/{x}/a(x=${u})}\">Go</button></form>",
    // A choice of links (#635): the branch taken, else (9) or then (10), is re-checked like a lone link.
    "<form method=\"post\" t:action=\"${u} == 'zz' ? @{/b} : @{/{x}/a(x=${u})}\"></form>",
    "<form method=\"post\" action=\"/transfer\"><button t:formaction=\"${u} != 'zz' ? @{/{x}/a(x=${u})} : @{/b}\">Go"
        + "</button></form>",
    // A resource URL (#756): a rooted path is re-checked too.
    "<script t:src=\"@{/a}\"></script>",
    "<script t:src=\"@{/{x}/a(x=${u})}\"></script>",
  };

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u\">\n" + body + "\n</div>")
        .compile()
        .assertSuccess();
  }

  private static String render(Compilation c, Links links, String u) {
    TestForms forms = new TestForms();
    forms.token = new Forms.CsrfToken("_csrf", "tok");
    return c.render("page", Map.of("u", u), new RenderContext(Locale.ROOT, Messages.NONE, links, forms));
  }

  /** A Links whose context path is not checked, and an encode hook that prepends {@code prefix}. */
  private static Links naive(String prefix) {
    return new Links() {
      @Override
      public String contextPath() {
        return prefix;
      }
    };
  }

  private static Links naiveEncode(String prefix) {
    return new DefaultLinks("", u -> prefix + u);
  }

  /** Positive: a context path of "/" or "/app/" is normalised, so every slot renders a single '/'. */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void contextPathWithTrailingSlashStaysSameOrigin(int i) {
    Compilation c = compile(SLOTS[i]);
    for (String root : new String[] {"", "/", "//", "/app", "/app/", "/app//"}) {
      String out = render(c, new DefaultLinks(root), "x");
      String expected = root.startsWith("/a") ? "/app/" : "/";
      assertTrue(out.contains("\"" + expected) || out.contains("url=" + expected), root + " -> " + out);
      assertFalse(out.contains("//a") || out.contains("//x"), root + " -> " + out);
    }
  }

  /** Negative: a custom context path or encode hook that makes any slot's link leave the origin fails the render. */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void customLinksLeavingTheOriginFailsTheRender(int i) {
    Compilation c = compile(SLOTS[i]);
    for (String prefix :
        new String[] {"/", "\\", "/\\", "/\t", " /", "https://evil.example", "//evil.example", "evil.example",
          "javascript:alert(1)//"}) {
      for (Links links : new Links[] {naive(prefix), naiveEncode(prefix)}) {
        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> render(c, links, "x"), "prefix " + prefix);
        assertTrue(e.getMessage().contains("same-origin path"), e.getMessage());
      }
    }
  }

  /** Negative: an empty or dot-segment path variable fails the render whatever the Links (it would give //a). */
  @ParameterizedTest
  @ValueSource(ints = {1, 4, 8, 9, 10, 12})
  void emptyPathVariableFailsTheRender(int i) {
    Compilation c = compile(SLOTS[i]);
    for (String bad : new String[] {"", ".", ".."}) {
      assertThrows(IllegalArgumentException.class, () -> render(c, naive(""), bad), bad);
      assertThrows(IllegalArgumentException.class, () -> render(c, new DefaultLinks("/"), bad), bad);
    }
  }

  /**
   * Positive: encoded slashes stay paths: a browser does not decode {@code %2F} or {@code %5C}
   * before finding the authority. A path variable's {@code /}, {@code \} and {@code %} are encoded.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 4, 8, 9, 10, 12})
  void encodedSlashesStayPaths(int i) {
    Compilation c = compile(SLOTS[i]);
    assertTrue(render(c, new DefaultLinks("/"), "/evil.example").contains("\"/%2Fevil.example/a\""));
    assertTrue(render(c, new DefaultLinks("/app/"), "\\evil.example").contains("\"/app/%5Cevil.example/a\""));
    assertTrue(render(c, naive(""), "%2F").contains("\"/%252F/a\""));
  }
}
