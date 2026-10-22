package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.messages.MessageBundle;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateModelException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.Test;

/** Codegen on hand-built documents, so it does not depend on the HTML or expression parsers. */
class TemplateGeneratorTest {
  private static final String XMLNS = " xmlns:t=\"https://tessera.dev\"";

  private static final String STATIC_SOURCE =
      "<!DOCTYPE html>\n"
          + "<html lang=\"en\""
          + XMLNS
          + " data-x='y'>\n"
          + "<body>\n"
          + "  <p class=\"a\"   id='b' hidden>Hi &amp; \"bye\" \u00e9 \\u000a \\</p>\n"
          + "  <br />\n"
          + "  <!-- c -->\n"
          + "</body>\n"
          + "</html>\n";

  private static Document staticDoc() {
    HandBuilt h = new HandBuilt("static.html", STATIC_SOURCE);
    Attribute id = new Attribute("id", "b", "b", '\'', h.pos("id="), h.pos("id=").advance("id='", 4));
    Attribute dataX = new Attribute("data-x", "y", "y", '\'', h.pos("data-x"), h.pos("data-x").advance("data-x='", 8));
    Element p =
        h.el(
            "p",
            List.of(h.attr("class", "a"), id, h.bare("hidden")),
            h.text("Hi &amp; \"bye\" \u00e9 \\u000a \\"));
    Element br = new Element("br", List.of(), List.of(), true, false, h.pos("<br"));
    Element body =
        h.el(
            "body",
            List.of(),
            h.text("\n  "),
            p,
            h.text("\n  "),
            br,
            h.text("\n  "),
            h.comment("<!-- c -->"),
            h.text("\n"));
    Element html =
        h.el("html", List.of(h.attr("lang", "en"), h.attr("xmlns:t", "https://tessera.dev"), dataX), h.text("\n"), body, h.text("\n"));
    return h.doc(h.doctype("<!DOCTYPE html>"), h.text("\n"), html, h.text("\n"));
  }

  @Test
  void staticTemplateRendersByteIdenticalMinusXmlns() {
    HandBuilt.Run run =
        HandBuilt.run(Compilation.builder(), pe -> List.of(HandBuilt.info(staticDoc(), List.of())));
    run.compilation().assertSuccess();
    assertEquals(List.of(), run.diagnostics().all());
    assertEquals(STATIC_SOURCE.replace(XMLNS, ""), run.compilation().render("static", Map.of()));
  }

  @Test
  void templateObjectRendersAndKnowsItsName() throws Exception {
    Compilation c =
        HandBuilt.run(Compilation.builder(), pe -> List.of(HandBuilt.info(staticDoc(), List.of())))
            .compilation()
            .assertSuccess();
    Class<?> cls = c.classLoader().loadClass("tessera.generated.StaticTemplate");
    dev.tessera.runtime.Template t = (dev.tessera.runtime.Template) cls.getMethod("of").invoke(null);
    assertEquals("static", t.templateName());
    assertEquals(STATIC_SOURCE.replace(XMLNS, ""), t.renderToString(RenderContext.defaults()));
  }

  @Test
  void blockElementOmitsItsTag() {
    String src = "<div><t:block>in<b>side</b></t:block></div>";
    HandBuilt h = new HandBuilt("block.html", src);
    Element b = h.el("b", List.of(), h.text("side"));
    Element block = h.el("t:block", List.of(), h.text("in"), b);
    Document doc = h.doc(h.el("div", List.of(), block));
    Compilation c =
        HandBuilt.run(Compilation.builder(), pe -> List.of(HandBuilt.info(doc, List.of()))).compilation().assertSuccess();
    assertEquals("<div>in<b>side</b></div>", c.render("block", Map.of()));
  }

  @Test
  void awkwardTextIsEmittedExactly() {
    StringBuilder big = new StringBuilder();
    while (big.length() < 8190) {
      big.append("abc\"\\u000a\\u0022\r\n\t\u0000\u2028</script>");
    }
    big.append("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00 end"); // surrogate pairs across the chunk boundary
    big.append("x".repeat(20000));
    String text = big.toString();
    String src = "<p>" + text + "</p>";
    HandBuilt h = new HandBuilt("big.html", src);
    Document doc = h.doc(h.el("p", List.of(), new Text(text, OutputContext.TEXT, h.pos(">").advance(">", 1))));
    Compilation c =
        HandBuilt.run(Compilation.builder(), pe -> List.of(HandBuilt.info(doc, List.of()))).compilation().assertSuccess();
    assertEquals(src, c.render("big", Map.of()));
  }

  // ---- t:params, typed methods and the registry adapter ----

  private static final String PARAMS_SOURCE =
      "<html" + XMLNS + " t:params=\"String title, List<String> items, int count, String out\">ok</html>";

  private static List<TemplateIndex.TemplateInfo> paramsTemplate(ProcessingEnvironment pe) {
    HandBuilt h = new HandBuilt("params.html", PARAMS_SOURCE);
    Element html =
        h.el(
            "html",
            List.of(
                h.attr("xmlns:t", "https://tessera.dev"),
                h.attr("t:params", "String title, List<String> items, int count, String out")),
            h.text("ok"));
    Elements el = pe.getElementUtils();
    Types types = pe.getTypeUtils();
    TypeMirror string = el.getTypeElement("java.lang.String").asType();
    TypeMirror list = types.getDeclaredType(el.getTypeElement("java.util.List"), string);
    TypeMirror intType = types.getPrimitiveType(TypeKind.INT);
    List<TemplateIndex.Param> params =
        List.of(
            new TemplateIndex.Param("title", string, string.toString()),
            new TemplateIndex.Param("items", list, list.toString()),
            new TemplateIndex.Param("count", intType, intType.toString()),
            new TemplateIndex.Param("out", string, string.toString()));
    return List.of(HandBuilt.info(h.doc(html), params));
  }

  private static Compilation paramsCompilation() {
    return HandBuilt.run(Compilation.builder(), TemplateGeneratorTest::paramsTemplate).compilation().assertSuccess();
  }

  @Test
  void paramsProduceTypedOfAndRenderMethods() throws Exception {
    Compilation c = paramsCompilation();
    Class<?> cls = c.classLoader().loadClass("tessera.generated.ParamsTemplate");
    Method of = cls.getMethod("of", String.class, List.class, int.class, String.class);
    assertTrue(Modifier.isStatic(of.getModifiers()));
    Method render =
        cls.getMethod("render", String.class, List.class, int.class, String.class, RenderContext.class, Output.class);
    assertTrue(Modifier.isStatic(render.getModifiers()));
    dev.tessera.runtime.Template t = (dev.tessera.runtime.Template) of.invoke(null, "T", List.of(), 1, "o");
    assertEquals("<html>ok</html>", t.renderToString(RenderContext.defaults()));
    String src = c.generatedSource("tessera.generated.ParamsTemplate");
    assertTrue(src.contains("java.lang.String out$"), src);
  }

  @Test
  void paramNamedLikeAConstantCannotShadowIt() {
    String src = "<html" + XMLNS + " t:params=\"String S0\">ok</html>";
    Compilation c =
        HandBuilt.run(
                Compilation.builder(),
                pe -> {
                  HandBuilt h = new HandBuilt("shadow.html", src);
                  Element html =
                      h.el(
                          "html",
                          List.of(h.attr("xmlns:t", "https://tessera.dev"), h.attr("t:params", "String S0")),
                          h.text("ok"));
                  TypeMirror string = pe.getElementUtils().getTypeElement("java.lang.String").asType();
                  return List.of(
                      HandBuilt.info(h.doc(html), List.of(new TemplateIndex.Param("S0", string, string.toString()))));
                })
            .compilation()
            .assertSuccess();
    assertEquals("<html>ok</html>", c.render("shadow", Map.of("S0", "<script>")));
  }

  @Test
  void registryListsParamsAndRendersAWellTypedModel() {
    Compilation c = paramsCompilation();
    TemplateEntry e = c.registry().find("params").orElseThrow();
    assertEquals(
        List.of(
            new TemplateEntry.Param("title", "java.lang.String"),
            new TemplateEntry.Param("items", "java.util.List<java.lang.String>"),
            new TemplateEntry.Param("count", "int"),
            new TemplateEntry.Param("out", "java.lang.String")),
        e.params());
    assertEquals(
        "<html>ok</html>",
        c.render("params", Map.of("title", "T", "items", List.of("a"), "count", 3, "out", "o")));
    assertTrue(c.registry().find("nope").isEmpty());
    assertTrue(c.registry().find(null).isEmpty());
    assertEquals(1, c.registry().all().size());
  }

  @Test
  void registryPassesNullForMissingParams() {
    Compilation c = paramsCompilation();
    assertEquals("<html>ok</html>", c.render("params", Map.of("count", 3)));
  }

  @Test
  void registryRejectsWrongTypedModelValue() {
    Compilation c = paramsCompilation();
    TemplateModelException e =
        assertThrows(TemplateModelException.class, () -> c.render("params", Map.of("title", 42, "count", 1)));
    assertTrue(e.getMessage().contains("'title'"), e.getMessage());
    assertTrue(e.getMessage().contains("java.lang.String"), e.getMessage());
    assertTrue(e.getMessage().contains("java.lang.Integer"), e.getMessage());
    assertTrue(e.getMessage().contains("params"), e.getMessage());

    e = assertThrows(TemplateModelException.class, () -> c.render("params", Map.of("items", "x", "count", 1)));
    assertTrue(e.getMessage().contains("'items'") && e.getMessage().contains("java.util.List"), e.getMessage());

    e = assertThrows(TemplateModelException.class, () -> c.render("params", Map.of("count", "3")));
    assertTrue(e.getMessage().contains("'count'") && e.getMessage().contains("java.lang.String"), e.getMessage());
  }

  @Test
  void registryRejectsMissingPrimitive() {
    Compilation c = paramsCompilation();
    Map<String, Object> model = new HashMap<>();
    model.put("count", null);
    TemplateModelException e = assertThrows(TemplateModelException.class, () -> c.render("params", model));
    assertTrue(e.getMessage().contains("'count'") && e.getMessage().contains("missing"), e.getMessage());
  }

  @Test
  void servicesFileNamesTheRegistry() {
    Compilation c = paramsCompilation();
    assertEquals(
        "tessera.generated.TesseraRegistry\n",
        c.generatedResource("META-INF/services/dev.tessera.runtime.TemplateRegistry"));
  }

  @Test
  void generatedSourcesContainNoReflection() {
    HandBuilt.Run run =
        HandBuilt.run(
            Compilation.builder(),
            pe -> {
              List<TemplateIndex.TemplateInfo> all = new java.util.ArrayList<>(paramsTemplate(pe));
              all.add(HandBuilt.info(staticDoc(), List.of()));
              return all;
            });
    run.compilation().assertSuccess();
    assertEquals(3, run.files().stream().filter(f -> f.kind() == GeneratedFile.Kind.JAVA).count());
    for (GeneratedFile f : run.files()) {
      for (String banned :
          List.of("java.lang.reflect", "java.lang.invoke", "MethodHandle", "Class.forName", "getDeclared", "setAccessible", "getMethod")) {
        assertFalse(f.content().contains(banned), f.name() + " contains " + banned);
      }
    }
  }

  // ---- errors ----

  private static final String BAD_SOURCE =
      "<html" + XMLNS + ">\n"
          + "<div t:params=\"String x\" t:import=\"java.util.*\">a</div>\n"
          + "<p t:text=\"a\" t:utext=\"b\">x</p>\n"
          + "<i t:insert=\"a\" t:replace=\"b\">y</i>\n"
          + "</html>";

  private static Document badDoc() {
    HandBuilt h = new HandBuilt("bad.html", BAD_SOURCE);
    List<Node> kids =
        List.of(
            h.text("\n"),
            h.el("div", List.of(h.attr("t:params", "String x"), h.attr("t:import", "java.util.*")), h.text("a")),
            h.text("\n"),
            h.el("p", List.of(h.attr("t:text", "a"), h.attr("t:utext", "b")), h.text("x")),
            h.text("\n"),
            h.el("i", List.of(h.attr("t:insert", "a"), h.attr("t:replace", "b")), h.text("y")),
            h.text("\n"));
    Element html =
        new Element("html", List.of(h.attr("xmlns:t", "https://tessera.dev")), kids, false, true, h.pos("<html"));
    return h.doc(html);
  }

  private static List<String> errors(Diagnostics d) {
    return d.all().stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).map(Diagnostic::toString).toList();
  }

  private static void assertHasError(List<String> errors, String... fragments) {
    assertTrue(
        errors.stream().anyMatch(e -> List.of(fragments).stream().allMatch(e::contains)),
        "no error with " + List.of(fragments) + " in " + errors);
  }

  @Test
  void misplacedCoreAttributesAndExclusivePairsAreErrorsWithPositions() {
    HandBuilt.Run run = HandBuilt.run(Compilation.builder(), pe -> List.of(HandBuilt.info(badDoc(), List.of())));
    List<String> errors = errors(run.diagnostics());
    assertHasError(errors, "bad.html:2:6: error: t:params is only allowed on the root element");
    assertHasError(errors, "bad.html:2:26: error: t:import is only allowed on the root element");
    assertHasError(errors, "bad.html:3:15:", "t:text and t:utext cannot be used on the same element");
    assertHasError(errors, "bad.html:4:17:", "t:insert and t:replace cannot be used on the same element");
  }

  @Test
  void templateWithErrorsGeneratesNothing() {
    HandBuilt.Run run =
        HandBuilt.run(
            Compilation.builder(),
            pe -> List.of(HandBuilt.info(badDoc(), List.of()), HandBuilt.info(staticDoc(), List.of())));
    assertTrue(run.diagnostics().hasErrors());
    assertTrue(
        run.files().stream().noneMatch(f -> f.name().equals("tessera.generated.BadTemplate")), run.files().toString());
    assertTrue(run.files().stream().anyMatch(f -> f.name().equals("tessera.generated.StaticTemplate")));
  }

  @Test
  void coreAttributesOnTheRootAreAccepted() {
    Compilation c = paramsCompilation();
    assertNotNull(c.generatedSource("tessera.generated.ParamsTemplate"));
  }

  // ---- TemplateIndex ----

  private static CompilerEnv plainEnv(Diagnostics d, String pkg) {
    return new CompilerEnv(null, null, d, pkg, MessageBundle.unchecked(), List.of());
  }

  private static Document emptyDoc(String path) {
    String view = path.substring(0, path.length() - ".html".length());
    return new Document(new TemplateSource(view, path, ""), List.of());
  }

  @Test
  void indexAssignsClassNames() {
    Diagnostics d = new Diagnostics();
    CompilerEnv env = plainEnv(d, "com.example.views");
    TemplateIndex index =
        TemplateIndex.build(
            List.of(emptyDoc("welcome.html"), emptyDoc("owners/find-owners.html")),
            new ExpressionParser(),
            new ExpressionCompiler(env),
            env);
    assertEquals(List.of(), d.all());
    assertEquals("com.example.views.WelcomeTemplate", index.template("welcome").orElseThrow().className());
    assertEquals(
        "com.example.views.owners.FindOwnersTemplate", index.template("owners/find-owners").orElseThrow().className());
    assertTrue(index.template("welcome").orElseThrow().params().isEmpty());
  }

  @Test
  void indexReportsClassNameCollisions() {
    Diagnostics d = new Diagnostics();
    CompilerEnv env = plainEnv(d, "x");
    TemplateIndex.build(
        List.of(emptyDoc("main-page.html"), emptyDoc("mainPage.html")), new ExpressionParser(), new ExpressionCompiler(env), env);
    assertHasError(errors(d), "mainPage.html:1:1", "x.MainPageTemplate", "main-page.html");
  }
}
