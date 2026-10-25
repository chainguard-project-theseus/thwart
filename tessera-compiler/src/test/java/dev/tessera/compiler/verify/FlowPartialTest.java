package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.Engines;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.Template;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #396: the compiler marks a template a verified {@code FLOW} partial only if its output can't
 * change the parser state where a {@code SafeHtml} or {@code Fragment} value is accepted, and
 * {@code SafeHtml.of} and {@code Fragment.of} refuse every other template. The negatives are the
 * partials the #395 review found: each passes the verifier on its own, and each changes the state
 * at a position the {@code FLOW} predicate accepts.
 */
class FlowPartialTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  /** Partials that aren't FLOW content, with words the reason must contain. */
  private static final String[][] NOT_FLOW = {
    {"li", "<li " + NS + " t:params=\"String a\" t:text=\"a\">x</li>", "list item"},
    {"liInDiv", "<div " + NS + " t:params=\"String a\"><li t:text=\"a\">x</li></div>", "after <ul><li>"},
    {"dd", "<dd " + NS + " t:params=\"String a\" t:text=\"a\">x</dd>", "<dd>"},
    {"dt", "<dt " + NS + " t:params=\"String a\" t:text=\"a\">x</dt>", "<dt>"},
    {"form", "<form " + NS + " t:params=\"String a\" method=\"get\" action=\"/s\"><input name=\"q\" t:value=\"a\"></form>", "<form>"},
    {"formInDiv", "<div " + NS + " t:params=\"String a\"><form method=\"get\" action=\"/s\"><input name=\"q\" t:value=\"a\"></form></div>", "<form>"},
    {"bb", "<span " + NS + " t:params=\"String a\"><b><b t:text=\"a\">x</b></b></span>", "<b> inside another <b>"},
    {"bbAttributes", "<span " + NS + " t:params=\"String a\"><b class=\"x\"><b class=\"x\" t:text=\"a\">x</b></b></span>", "<b> inside another <b>"},
    {"iInEmInI", "<div " + NS + " t:params=\"String a\"><i><em><i t:text=\"a\">x</i></em></i></div>", "<i> inside another <i>"},
    {"tr", "<tr " + NS + " t:params=\"String a\"><td t:text=\"a\">x</td></tr>", "table part"},
    {"page", "<!DOCTYPE html>\n<html " + NS + " t:params=\"String a\"><head><title>t</title></head><body><p t:text=\"a\">x</p></body></html>", "page"},
    {"htmlRoot", "<html " + NS + " t:params=\"String a\"><body><p t:text=\"a\">x</p></body></html>", "page"},
  };

  /** Ordinary partials: FLOW. */
  private static final String[][] FLOW = {
    {"div", "<div " + NS + " class=\"card\" t:params=\"String a\"><h2 t:text=\"a\">t</h2><p>Hi <b t:text=\"a\">x</b>.</p></div>"},
    {"section", "<section " + NS + " t:params=\"String a\"><ul><li t:text=\"a\">x</li><li>y</li></ul>"
        + "<dl><dt>k</dt><dd t:text=\"a\">v</dd></dl><table><tr><td t:text=\"a\">c</td></tr></table></section>"},
    {"span", "<span " + NS + " t:params=\"String a\" t:text=\"a\">x</span>"},
    {"link", "<a " + NS + " t:params=\"String href, String name\" t:href=\"href\" class=\"text-default\" t:text=\"name\">name</a>"},
    {"text", "<t:block " + NS + " t:params=\"String a\">Hello, [[${a}]]!</t:block>"},
    {"siblings", "<div " + NS + " t:params=\"String a\"><b>1</b><b t:text=\"a\">2</b><i><b>3</b></i></div>"},
    {"sanitized", "<div " + NS + " t:params=\"String a\" t:utext=\"${html.sanitizeRich(a)}\">x</div>"},
    {"fragmentCall", "<div " + NS + " t:params=\"String a\"><p t:replace=\"~{:: note(a)}\">n</p>"
        + "<p t:fragment=\"note(String text)\">[[${text}]]</p></div>"},
    {"loop", "<div " + NS + " t:import=\"java.util.List\" t:params=\"List<String> items\">"
        + "<p t:each=\"i : items\" t:text=\"i\">x</p></div>"},
    {"rawText", "<div " + NS + " t:params=\"String a\"><pre t:text=\"a\">p</pre><textarea name=\"t\" t:text=\"a\">t</textarea>"
        + "<script>var a = [[${a}]];</script><style>p { color: red }</style></div>"},
    {"templateElement", "<div " + NS + " t:params=\"String a\"><template><p t:text=\"a\">x</p></template></div>"},
  };

  /**
   * Partials the producer-promise checks used to mark as not FLOW (#396) whose markup parses only by
   * error recovery: since #406 each is a build error. The parser closes an element before its end
   * tag, which then closes nothing; a <caption> outside a table is a stray start tag; a <p> can't
   * hold a <div>.
   */
  private static final String[][] PARSE_ERRORS = {
    {"caption", "<caption " + NS + " t:params=\"String a\" t:text=\"a\">x</caption>"},
    {"headings", "<div " + NS + " t:params=\"String a\"><h1><h2 t:text=\"a\">x</h2></h1></div>"},
    {"ddInDt", "<dl " + NS + " t:params=\"String a\"><dt>k<dd t:text=\"a\">v</dd></dt></dl>"},
    {"buttonInButton", "<div " + NS + " t:params=\"String a\"><button><div><button t:text=\"a\">x</button></div></button></div>"},
    {"pAroundDiv", "<div " + NS + " t:params=\"String a\"><p><div t:text=\"a\">x</div></p><address><p><address>y</address></p></address></div>"},
  };

  @Test
  void partialsThatParseOnlyByErrorRecoveryAreBuildErrors() {
    for (String[] t : PARSE_ERRORS) {
      Compilation.builder().template("p/" + t[0] + ".html", t[1]).compile()
          .assertError("p/" + t[0] + ".html:1:", "[verifier:PARSE_ERROR]");
    }
  }

  private static Compilation compile(String[][] templates) {
    Compilation.Builder b = Compilation.builder();
    for (String[] t : templates) {
      b.template("p/" + t[0] + ".html", t[1]);
    }
    return b.compile().assertSuccess();
  }

  @Test
  void breakingPartialsAreNotMarkedAndTheRuntimeRefusesThem() throws Exception {
    Compilation c = compile(NOT_FLOW);
    for (String[] t : NOT_FLOW) {
      String why = whyNot(c, "p/" + t[0]);
      assertTrue(why != null && why.contains(t[2]), t[0] + ": " + why);
      Template template = instance(c, t[0]);
      assertFalse(template.verifiedFlowPartial(), t[0]);
      assertFalse(c.generatedSource(className(t[0])).contains("verifiedFlowPartial"), t[0]);
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> SafeHtml.of(template, RenderContext.defaults()), t[0]);
      assertTrue(e.getMessage().contains("'p/" + t[0] + "'") && e.getMessage().contains("<div>"), e.getMessage());
      e = assertThrows(IllegalArgumentException.class, () -> Fragment.of(template), t[0]);
      assertTrue(e.getMessage().startsWith("Tessera: Fragment.of"), e.getMessage());
    }
  }

  @Test
  void ordinaryPartialsAreMarkedAndRenderAsBefore() throws Exception {
    Compilation c = compile(FLOW);
    for (String[] t : FLOW) {
      assertNull(whyNot(c, "p/" + t[0]), t[0]);
      Template template = instance(c, t[0]);
      assertTrue(template.verifiedFlowPartial(), t[0]);
      String direct = template.renderToString(RenderContext.defaults());
      assertEquals(direct, SafeHtml.of(template, RenderContext.defaults()).html(), t[0]);
      StringOutput out = new StringOutput();
      Fragment.of(template).render(RenderContext.defaults(), out);
      assertEquals(direct, out.toString(), t[0]);
    }
  }

  /** The reviewer's contexts: each negative changes the state there, so the mark matters. */
  @Test
  void eachNegativeChangesTheStateWhereFlowIsAccepted() throws Exception {
    Compilation c = compile(NOT_FLOW);
    String[][] cases = {
      {"li", "<!DOCTYPE html><ul><li>"},
      {"liInDiv", "<!DOCTYPE html><ul><li>"},
      {"dd", "<!DOCTYPE html><dl><dd>"},
      {"dt", "<!DOCTYPE html><dl><dd>"},
      {"form", "<!DOCTYPE html><form>"},
      {"formInDiv", "<!DOCTYPE html><form>"},
      {"bb", "<!DOCTYPE html><b><b>"},
      {"bbAttributes", "<!DOCTYPE html><b class=\"x\"><b class=\"x\">"},
      {"tr", "<!DOCTYPE html><table><tr><td>"},
      {"tr", "<!DOCTYPE html><template>"},
      {"page", "<!DOCTYPE html><body><div>"},
    };
    for (String[] k : cases) {
      String html = instance(c, k[0]).renderToString(RenderContext.defaults());
      boolean changed = false;
      for (Parser entry : entries(k[1])) {
        assertNull(ContentKinds.problem(ContentKinds.Kind.FLOW, entry.state()), k[1]);
        Parser p = entry.fork();
        p.feed(html);
        changed |= !p.state().equals(entry.state());
      }
      assertTrue(changed, k[0] + " in " + k[1]);
    }
  }

  @Test
  void everyProbeIsAFlowPosition() throws EngineException {
    for (String probe : ContentKinds.PARTIAL_PROBES) {
      dev.tessera.compiler.verify.engine.Engine e = Engines.CURRENT;
      Parser p = e.fragment(dev.tessera.compiler.verify.engine.OpenElement.HTML, "body", true);
      p.feed(probe);
      assertNull(ContentKinds.problem(ContentKinds.Kind.FLOW, p.state()), probe);
    }
  }

  /** A page that renders a FLOW partial in Java and inserts it where the verifier accepts FLOW. */
  @Test
  void aMarkedPartialInsertsIntoAPage() throws Exception {
    Compilation c = Compilation.builder()
        .template("badge.html", "<span " + NS + " class=\"badge\" t:params=\"String a\" t:text=\"a\">x</span>")
        .template("page.html", "<!DOCTYPE html>\n<html " + NS + " t:params=\"dev.tessera.runtime.SafeHtml b\"><body>"
            + "<form method=\"get\" action=\"/s\"><ul><li t:utext=\"b\">x</li></ul></form></body></html>")
        .compile()
        .assertSuccess();
    Template badge = (Template) c.classLoader().loadClass("tessera.generated.BadgeTemplate")
        .getMethod("of", String.class).invoke(null, "<i>");
    SafeHtml b = SafeHtml.of(badge, RenderContext.defaults());
    assertTrue(c.render("page", Map.of("b", b)).contains("<li><span class=\"badge\">&lt;i&gt;</span></li>"));
    assertFalse(((Template) c.classLoader().loadClass("tessera.generated.PageTemplate")
        .getMethod("of", SafeHtml.class).invoke(null, b)).verifiedFlowPartial());
  }

  // ---- helpers ----

  private static String whyNot(Compilation c, String view) {
    Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>();
    all.put(view, c.skeleton(view));
    return new ContextVerifier(all).whyNotFlowPartial(c.skeleton(view));
  }

  private static String className(String name) {
    return "tessera.generated.p." + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Template";
  }

  private static Template instance(Compilation c, String name) throws Exception {
    Class<?> k = c.classLoader().loadClass(className(name));
    for (Method m : k.getMethods()) {
      if (m.getName().equals("of")) {
        Object[] args = new Object[m.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
          args[i] = m.getParameterTypes()[i] == String.class ? "v" : java.util.List.of("v");
        }
        return (Template) m.invoke(null, args);
      }
    }
    throw new AssertionError("no of() on " + k);
  }

  private static java.util.List<Parser> entries(String context) throws EngineException {
    java.util.List<Parser> out = new java.util.ArrayList<>();
    dev.tessera.compiler.verify.engine.Engine e = Engines.CURRENT;
    Parser p = e.document(true);
    p.feed(context);
    out.add(p);
    return out;
  }
}
