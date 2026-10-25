package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A POST form whose action is a link with a literal scheme or {@code //} gets no CSRF token
 * (#285) and a build warning saying so, since such a link is most likely the application's own
 * host written absolutely, where Spring Security then rejects the POST. The warning names the
 * template position; nothing else warns, and the build still succeeds.
 */
class CsrfAbsoluteLinkWarningTest {
  private static final String WARNING = "so this POST form gets no CSRF token";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("form.html", "<div xmlns:t=\"https://tessera.dev\" t:params=\"String u, java.util.List<String> us\">\n" + body + "</div>")
        .compile()
        .assertSuccess();
  }

  private static List<String> warnings(Compilation c) {
    return c.diagnostics().stream().filter(d -> d.contains(WARNING)).toList();
  }

  @Test
  void absoluteLinkOnPostFormWarnsWithPosition() {
    Compilation c = compile("  <form method=\"post\" t:action=\"@{https://www.example.com/owners}\">x</form>\n");
    List<String> w = warnings(c);
    assertEquals(1, w.size(), c.diagnostics().toString());
    assertEquals(
        "WARNING: form.html:2:23: warning: t:action=\"@{https://www.example.com/owners}\" has a scheme or starts"
            + " with //, so this POST form gets no CSRF token: write a path such as @{/...} if it posts to this"
            + " application, or a static action=\"...\" if another site is meant",
        w.get(0));
    assertTrue(c.success());
  }

  @Test
  void otherAbsoluteShapesWarn() {
    for (String form :
        new String[] {
          "<form method=\"POST\" t:action=\"@{//cdn.example/x}\">x</form>",
          "<form method=\"post\" t:action=\"@{http://h/o/{id}(id=1)}\">x</form>",
          "<form method=\"post\" t:attr=\"action=@{https://h/x}\">x</form>",
          "<form t:action=\"@{https://h/x}\" method=\"post\">x</form>",
          "<form method=\"post\" t:action=\"@{https://h/x}\" t:if=\"u != null\">x</form>",
          "<div t:each=\"s : us\"><form method=\"post\" t:action=\"@{https://h/x}\">x</form></div>",
          "<form method=\"post\" t:action=\"@{http:x}\">x</form>",
          "<form method=\"post\" t:action=\"@{https:/x}\">x</form>",
          // Only a method assignment makes the method computed.
          "<form method=\"post\" t:attr=\"action=@{https://h/x}, data-method=u\">x</form>",
        }) {
      assertEquals(1, warnings(compile(form)).size(), form);
    }
  }

  @Test
  void linkErrorIsNotAlsoWarnedAbout() {
    Compilation c =
        Compilation.builder()
            .template("form.html", "<div xmlns:t=\"https://tessera.dev\">\n<form method=\"post\" t:action=\"@{mailto:a@b}\">x</form></div>")
            .compile()
            .assertError("mailto");
    assertEquals(List.of(), warnings(c), c.diagnostics().toString());
  }

  @Test
  void attrActionWarningNamesTheAttr() {
    List<String> w = warnings(compile("<form method=\"post\" t:attr=\"action=@{https://h/x}\">x</form>"));
    assertEquals(1, w.size());
    assertTrue(w.get(0).startsWith("WARNING: form.html:2:29: warning: t:attr action=\"@{https://h/x}\" has a scheme"), w.get(0));
  }

  @Test
  void nothingElseWarns() {
    for (String form :
        new String[] {
          // Same-origin links keep the token: no warning.
          "<form method=\"post\" t:action=\"@{/x}\">x</form>",
          "<form method=\"post\" t:action=\"@{x}\">x</form>",
          "<form method=\"post\" t:action=\"@{?page=2}\">x</form>",
          "<form method=\"post\" t:action=\"@{/o/{id}(id=1)}\">x</form>",
          "<form method=\"post\" t:action=\"@{~/x}\">x</form>",
          // A static cross-origin action is the author's choice (Thymeleaf parity: never had a token).
          "<form method=\"post\" action=\"https://other.example/\">x</form>",
          // Not a POST form, or the method is computed (decided at render time).
          "<form method=\"get\" t:action=\"@{https://h/x}\">x</form>",
          // The method must be exactly post, untrimmed, as CsrfInjector reads it: these submit as GET.
          "<form method=\" post\" t:action=\"@{https://h/x}\">x</form>",
          "<form method=\"post \" t:action=\"@{https://h/x}\">x</form>",
          "<form t:action=\"@{https://h/x}\">x</form>",
          "<form t:method=\"u\" method=\"post\" t:action=\"@{https://h/x}\">x</form>",
          "<form method=\"post\" t:action=\"@{https://h/x}\" t:method=\"u\">x</form>",
          "<form method=\"post\" t:attr=\"method=u\" t:action=\"@{https://h/x}\">x</form>",
          "<form method=\"post\" t:action=\"@{https://h/x}\" t:attr=\"method=u\">x</form>",
          "<form method=\"post\" t:attr=\"action=@{https://h/x}, method=u\">x</form>",
          // A removed form has no token to lose.
          "<form method=\"post\" t:action=\"@{https://h/x}\" t:remove=\"all\">x</form>",
          "<form method=\"post\" t:action=\"@{https://h/x}\" t:remove=\"tag\">x</form>",
          // A computed action is decided at render time.
          "<form method=\"post\" t:action=\"u\">x</form>",
          // An absolute link elsewhere is fine.
          "<a t:href=\"@{https://h/x}\">x</a><form method=\"post\"><button t:formaction=\"@{/x}\">y</button></form>",
        }) {
      Compilation c = compile(form);
      assertEquals(List.of(), warnings(c), form);
    }
  }
}
