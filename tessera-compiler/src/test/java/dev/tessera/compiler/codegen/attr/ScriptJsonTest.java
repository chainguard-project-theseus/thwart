package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Records and {@code @TemplateView} beans as JSON inside {@code <script>} (#121). */
class ScriptJsonTest {
  private static final String CHART =
      "package com.example;\n"
          + "@dev.tessera.runtime.TemplateView public class Chart {\n"
          + "  private final String title; private final boolean visible; private final java.util.List<Integer> data;\n"
          + "  public Chart(String title, boolean visible, java.util.List<Integer> data) {\n"
          + "    this.title = title; this.visible = visible; this.data = data; }\n"
          + "  public String getURL() { return \"/c\"; }\n"
          + "  public java.util.List<Integer> getData() { return data; }\n"
          + "  public boolean isVisible() { return visible; }\n"
          + "  public String getTitle() { return title; }\n"
          + "  public Boolean isBoxed() { return true; }\n"
          + "  public String label() { return \"not a getter\"; }\n"
          + "  public String getWith(int x) { return \"has a parameter\"; }\n"
          + "  public static String getStatic() { return \"static\"; }\n"
          + "  String getHidden() { return \"not public\"; }\n"
          + "  public ClassLoader getLoader() { return getClass().getClassLoader(); }\n"
          + "  public Class<?> getType() { return Chart.class; }\n"
          + "  public Object getAnything() { return \"object\"; }\n"
          + "  public java.util.List<Thread> getThreads() { return java.util.Collections.emptyList(); }\n"
          + "}\n";

  private static Compilation compile(String params, String body) {
    return Compilation.builder()
        .java("com.example.Chart", CHART)
        .java("com.example.Point", "package com.example; public record Point(int x, String label) {}")
        .java(
            "com.example.Shapes",
            "package com.example; public record Shapes(java.util.List<Point> list, java.util.Map<String, Point> byName,"
                + " java.util.Optional<Point> maybe, Point[] array, java.util.List<String> tags) {}")
        .java(
            "com.example.Tree",
            "package com.example; public record Tree(String name, java.util.List<Tree> children) {}")
        .java(
            "com.example.Page",
            "package com.example; public record Page<T>(java.util.List<T> content, int total) {}")
        .java(
            "com.example.Link",
            "package com.example; @dev.tessera.runtime.TemplateView public class Link {\n"
                + "  private Link next; public Link getNext() { return next; } public void setNext(Link n) { next = n; } }")
        .java("com.example.Raw", "package com.example; public record Raw(dev.tessera.runtime.SafeHtml html) {}")
        .java("com.example.Pojo", "package com.example; public class Pojo { public String getName() { return \"p\"; } }")
        .java("com.example.Holder", "package com.example; public record Holder(Pojo pojo) {}")
        .template("page.html", "<div t:import=\"com.example.*\" t:params=\"" + params + "\">" + body + "</div>")
        .compile();
  }

  @Test
  void javaUtilImplementationsSerializeAsTheirInterface() {
    // #135: an ArrayList is a List and a TreeMap a Map, in scripts and in t:each.
    Compilation c = compile(
            "ArrayList<Point> l, TreeMap<String, Point> m",
            "<script>var l = [[l]]; var m = [[m]];</script><p t:each=\"e : m\" t:text=\"e.key\"></p>")
        .assertSuccess();
    Object a = c.newInstance("com.example.Point", 1, "a");
    Object b = c.newInstance("com.example.Point", 2, "b");
    assertEquals(
        "<div><script>var l = ([{\"x\":1,\"label\":\"a\"}]); var m = ({\"x\":{\"x\":1,\"label\":\"a\"},"
            + "\"y\":{\"x\":2,\"label\":\"b\"}});</script><p>x</p><p>y</p></div>",
        c.render("page", model("l", new java.util.ArrayList<>(List.of(a)), "m", new java.util.TreeMap<>(Map.of("y", b, "x", a)))));
  }

  @Test
  void recordIsAnObjectOfItsComponentsInOrder() {
    Compilation c = compile("Point p", "<script>var p = [[p]];</script>").assertSuccess();
    Object p = c.newInstance("com.example.Point", 3, "a");
    assertEquals("<div><script>var p = ({\"x\":3,\"label\":\"a\"});</script></div>", c.render("page", model("p", p)));
    assertEquals("<div><script>var p = (null);</script></div>", c.render("page", model("p", null)));
  }

  @Test
  void templateViewBeanUsesGettersNamedAndOrderedLikeJackson() {
    Compilation c = compile("Chart chart", "<script>var chart = [[chart]];</script>").assertSuccess();
    Object chart = c.newInstance("com.example.Chart", "Sales", true, List.of(1, 2));
    // As Thymeleaf 3.1 with Jackson 2.17: fields order the properties (title, visible, data), then
    // the getter-only ones; getURL is "url".
    assertEquals(
        "<div><script>var chart = ({\"title\":\"Sales\",\"visible\":true,\"data\":[1,2],\"url\":\"\\/c\",\"boxed\":true});</script></div>",
        c.render("page", model("chart", chart)));
  }

  @Test
  void neverViewSafeGettersAreNotSerialized() {
    Compilation c = compile("Chart chart", "<script>var chart = [[chart]];</script>").assertSuccess();
    String out = c.render("page", model("chart", c.newInstance("com.example.Chart", "t", false, List.of())));
    for (String hidden : List.of("loader", "type", "anything", "threads", "class")) {
      assertFalse(out.contains("\"" + hidden + "\""), hidden + " leaked: " + out);
    }
    String generated = c.generatedSource("tessera.generated.PageTemplate");
    assertFalse(generated.contains("getLoader"), generated);
    assertFalse(generated.contains("getAnything"), generated);
    assertFalse(generated.contains("getClass"), generated);
  }

  @Test
  void stringsInsideBeansAreScriptEscaped() {
    Compilation c = compile("Point p", "<script>var p = [[p]];</script>").assertSuccess();
    Object p = c.newInstance("com.example.Point", 1, "</script><script>alert(1)</script>");
    assertEquals(
        "<div><script>var p = ({\"x\":1,\"label\":\"\\u003c\\/script\\u003e\\u003cscript\\u003ealert(1)\\u003c\\/script\\u003e\"});"
            + "</script></div>",
        c.render("page", model("p", p)));
  }

  @Test
  void beansNestInCollectionsMapsOptionalsAndArrays() throws Exception {
    Compilation c = compile("Shapes s, List<Point> ps", "<script>var v = [[s]]; var w = [[ps]];</script>").assertSuccess();
    Object a = c.newInstance("com.example.Point", 1, "a");
    Object b = c.newInstance("com.example.Point", 2, "b");
    Object[] array = (Object[]) java.lang.reflect.Array.newInstance(c.classLoader().loadClass("com.example.Point"), 1);
    array[0] = b;
    Object s = c.newInstance("com.example.Shapes", List.of(a, b), Map.of("k", a), Optional.of(b), array, List.of("t"));
    String pa = "{\"x\":1,\"label\":\"a\"}";
    String pb = "{\"x\":2,\"label\":\"b\"}";
    assertEquals(
        "<div><script>var v = ({\"list\":[" + pa + "," + pb + "],\"byName\":{\"k\":" + pa + "},\"maybe\":" + pb + ",\"array\":["
            + pb + "],\"tags\":[\"t\"]}); var w = ([" + pa + "]);</script></div>",
        c.render("page", model("s", s, "ps", List.of(a))));
    Object empty = c.newInstance("com.example.Shapes", null, null, Optional.empty(), null, null);
    assertEquals(
        "<div><script>var v = ({\"list\":null,\"byName\":null,\"maybe\":null,\"array\":null,\"tags\":null}); var w = ([]);</script></div>",
        c.render("page", model("s", empty, "ps", List.of())));
  }

  @Test
  void genericRecordUsesItsTypeArguments() {
    Compilation c = compile("Page<Point> page", "<script>var v = [[page]];</script>").assertSuccess();
    Object page = c.newInstance("com.example.Page", List.of(c.newInstance("com.example.Point", 1, "a")), 1);
    assertEquals(
        "<div><script>var v = ({\"content\":[{\"x\":1,\"label\":\"a\"}],\"total\":1});</script></div>",
        c.render("page", model("page", page)));
  }

  @Test
  void recursiveTypesSerializeAndCyclesFailBeforeWriting() throws Exception {
    Compilation c = compile("Tree t", "<script>var v = [[t]];</script>").assertSuccess();
    Object leaf = c.newInstance("com.example.Tree", "leaf", List.of());
    Object root = c.newInstance("com.example.Tree", "root", List.of(leaf));
    assertEquals(
        "<div><script>var v = ({\"name\":\"root\",\"children\":[{\"name\":\"leaf\",\"children\":[]}]});</script></div>",
        c.render("page", model("t", root)));

    Compilation cyclic = compile("Link l", "<p>before</p><script>var v = [[l]];</script>").assertSuccess();
    Object link = cyclic.newInstance("com.example.Link");
    link.getClass().getMethod("setNext", link.getClass()).invoke(link, link);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> cyclic.render("page", model("l", link)));
    assertTrue(e.getMessage().contains("nested deeper"), e.getMessage());
  }

  @Test
  void textOnJsonScriptSerializesBeans() {
    Compilation c = compile("Point p", "<script type=\"application/json\" t:text=\"p\"></script>").assertSuccess();
    assertEquals(
        "<div><script type=\"application/json\">{\"x\":1,\"label\":\"a\"}</script></div>",
        c.render("page", model("p", c.newInstance("com.example.Point", 1, "a"))));
  }

  @Test
  void propertyThatCannotBeSerializedIsBuildError() {
    compile("Raw r", "<script>var r = [[r]];</script>")
        .assertError("page.html:1:", "inside <script>", "property 'html' of com.example.Raw", "SafeHtml");
    compile("Holder h", "<script>var h = [[h]];</script>")
        .assertError("page.html:1:", "inside <script>", "property 'pojo'", "com.example.Pojo");
    compile("List<Raw> rs", "<script>var r = [[rs]];</script>").assertError("page.html:1:", "property 'html'");
  }

  @Test
  void typeThatIsNotViewSafeIsBuildError() {
    compile("Pojo p", "<script>var p = [[p]];</script>").assertError("page.html:1:", "not view-safe");
    compile("List<Pojo> p", "<script>var p = [[p]];</script>").assertError("page.html:1:", "not view-safe");
  }

  @Test
  void adversarialBeansExposeNothingTemplatesCannotRead() {
    Compilation c =
        Compilation.builder()
            .java(
                "com.example.Base",
                "package com.example; public class Base {\n"
                    + "  public ClassLoader getClassLoader() { return Base.class.getClassLoader(); }\n"
                    + "  public String getInherited() { return \"i\"; } }")
            .java(
                "com.example.Named",
                "package com.example; public interface Named {\n"
                    + "  default Object getSecret() { return \"s\"; }\n"
                    + "  default String getNick() { return \"n\"; } }")
            .java(
                "com.example.Evil",
                "package com.example; @dev.tessera.runtime.TemplateView public class Evil extends Base implements Named {\n"
                    + "  public java.util.Optional<Class<?>> getType() { return java.util.Optional.of(Evil.class); }\n"
                    + "  public java.util.Map<String, Thread> getThreads() { return java.util.Collections.emptyMap(); }\n"
                    + "  public java.lang.reflect.Method[] getMethods() { return new java.lang.reflect.Method[0]; }\n"
                    + "  public Runtime getRuntime() { return Runtime.getRuntime(); }\n"
                    + "  public String getName() { return \"e\"; } }")
            .java(
                "com.example.Rec",
                "package com.example; public record Rec(String name, Thread thread, ClassLoader loader,"
                    + " java.util.List<Object> objects) {}")
            .template("evil.html", "<div t:import=\"com.example.*\" t:params=\"Evil e\"><script>var v = [[e]];</script></div>")
            .template("rec.html", "<div t:import=\"com.example.*\" t:params=\"Rec r\"><script>var v = [[r]];</script></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><script>var v = ({\"name\":\"e\",\"inherited\":\"i\",\"nick\":\"n\"});</script></div>",
        c.render("evil", model("e", c.newInstance("com.example.Evil"))));
    assertEquals(
        "<div><script>var v = ({\"name\":\"a\"});</script></div>",
        c.render("rec", model("r", c.newInstance("com.example.Rec", "a", null, null, null))));
    for (String hidden : List.of("getClassLoader", "getSecret", "getType", "getThreads", "getMethods", "getRuntime",
        "thread()", "loader()", "objects()", "getClass")) {
      assertFalse(c.generatedSource("tessera.generated.EvilTemplate").contains(hidden), hidden);
      assertFalse(c.generatedSource("tessera.generated.RecTemplate").contains(hidden), hidden);
    }
    Compilation.builder()
        .java(
            "com.example.Worker",
            "package com.example; @dev.tessera.runtime.TemplateView public class Worker extends Thread {\n"
                + "  public String getLabel() { return \"w\"; } }")
        .template("w.html", "<div t:import=\"com.example.*\" t:params=\"Worker w\"><script>var v = [[w]];</script></div>")
        .compile()
        .assertError("w.html:1:", "java.lang.Thread");
  }
  @Test
  void scriptBreakersAreEscapedInNestedBeansAndPropertyNames() {
    Compilation c = Compilation.builder()
        .java("com.example.Value", "package com.example; public record Value(String a$name) {}")
        .template("page.html", "<script t:params=\"com.example.Value value\">var v = [[value]];</script>")
        .compile().assertSuccess();
    Object value = c.newInstance("com.example.Value", "*/ `${x}` </script>\u2028\u2029\ud800");
    assertEquals("<script>var v = ({\"a\\u0024name\":\"*\\/ \\u0060\\u0024\\u007bx}\\u0060 \\u003c\\/script\\u003e\\u2028\\u2029\\ud800\"});</script>",
        c.render("page", model("value", value)));
  }

  @Test
  void gettersRunOnceInPropertyOrderAndFailureWritesNoJson() {
    Compilation c = Compilation.builder()
        .java("com.example.Checked", "package com.example; @dev.tessera.runtime.TemplateView public class Checked {"
            + " private int calls; private final boolean fail; public Checked(boolean fail) { this.fail = fail; }"
            + " public String getFirst() { if (++calls != 1) throw new AssertionError(); return \"first\"; }"
            + " public String getSecond() { if (++calls != 2) throw new AssertionError();"
            + " if (fail) throw new IllegalArgumentException(\"getter failed\"); return \"second\"; } }")
        .template("page.html", "<script t:params=\"com.example.Checked value\">var v = [[value]];</script>")
        .compile().assertSuccess();
    assertEquals("<script>var v = ({\"first\":\"first\",\"second\":\"second\"});</script>",
        c.render("page", model("value", c.newInstance("com.example.Checked", false))));
    assertFailsBeforeJson(c, model("value", c.newInstance("com.example.Checked", true)));
  }

  @Test
  void invalidNestedMapKeysFailWithoutWritingJson() {
    Compilation c = compile("Shapes s", "<script>var v = [[s]];</script>").assertSuccess();
    Object p = c.newInstance("com.example.Point", 1, "first");
    Map<Object, Object> keys = new java.util.LinkedHashMap<>();
    keys.put("good", p);
    keys.put(42, p);
    Object shapes = c.newInstance("com.example.Shapes", List.of(p), keys, Optional.empty(), null, null);
    assertFailsBeforeJson(c, model("s", shapes));
    keys.remove(42);
    keys.put(null, p);
    assertFailsBeforeJson(c, model("s", shapes));
  }

  @Test
  void exactDepthBoundariesForBeansAndOptionalContainers() {
    assertDepthBoundary("com.example.Node", "package com.example; public record Node(Node next) {}", 64, false, false);
    assertDepthBoundary("com.example.Node", "package com.example; public record Node(java.util.Optional<Node> next) {}",
        32, true, false);
    assertDepthBoundary("com.example.Node", "package com.example; public record Node(java.util.List<java.util.Optional<Node>> next) {}",
        21, true, true);
  }

  private static void assertDepthBoundary(String type, String source, int accepted, boolean optional, boolean list) {
    Compilation c = Compilation.builder().java(type, source)
        .template("page.html", "<script t:params=\"" + type + " value\">var v = [[value]];</script>")
        .compile().assertSuccess();
    Object value = null;
    for (int i = 0; i < accepted; i++) {
      Object child = optional ? Optional.ofNullable(value) : value;
      value = c.newInstance(type, list ? List.of(child) : child);
    }
    assertTrue(c.render("page", model("value", value)).endsWith("</script>"));
    Object child = optional ? Optional.of(value) : value;
    Object excessive = c.newInstance(type, list ? List.of(child) : child);
    assertFailsBeforeJson(c, model("value", excessive));
  }

  @Test
  void scalarContainersWithinBeansKeepTheirRuntimeDepthLimit() {
    Compilation c = Compilation.builder()
        .java("com.example.Node", "package com.example; public record Node(java.util.Optional<String> text, Node next) {}")
        .template("page.html", "<script t:params=\"com.example.Node value\">var v = [[value]];</script>")
        .compile().assertSuccess();
    Object value = null;
    for (int i = 0; i < 63; i++) {
      value = c.newInstance("com.example.Node", Optional.of("ok"), value);
    }
    assertTrue(c.render("page", model("value", value)).endsWith("</script>"));
    Object excessive = c.newInstance("com.example.Node", Optional.of("ok"), value);
    assertFailsBeforeJson(c, model("value", excessive));
  }

  private static void assertFailsBeforeJson(Compilation c, Map<String, ?> model) {
    dev.tessera.runtime.internal.StringOutput out = new dev.tessera.runtime.internal.StringOutput();
    assertThrows(IllegalArgumentException.class,
        () -> c.registry().find("page").orElseThrow().renderer()
            .render(model, dev.tessera.runtime.RenderContext.defaults(), out));
    assertTrue(out.toString().endsWith("<script>var v = "), out.toString());
    assertFalse(out.toString().contains("{"), out.toString());
  }

  @Test
  void serializesEachPropertyBeforeCallingTheNextGetter() {
    Compilation c = Compilation.builder()
        .java("com.example.Mutable", "package com.example; @dev.tessera.runtime.TemplateView public class Mutable {"
            + " private final java.util.List<String> values = new java.util.ArrayList<>(java.util.Arrays.asList(\"original\"));"
            + " public java.util.List<String> getValues() { return values; }"
            + " public String getLast() { values.set(0, \"changed\"); return \"last\"; } }")
        .template("page.html", "<script t:params=\"com.example.Mutable value\">var v = [[value]];</script>")
        .compile().assertSuccess();
    assertEquals("<script>var v = ({\"values\":[\"original\"],\"last\":\"last\"});</script>",
        c.render("page", model("value", c.newInstance("com.example.Mutable"))));
  }
  @Test
  void beanContainersCompileAtJava8Floor() {
    Compilation c = Compilation.builder().release(8)
        .java("com.example.Bean", "package com.example; @dev.tessera.runtime.TemplateView public class Bean {"
            + " public String getName() { return \"ok\"; } }")
        .template("page.html", "<script t:params=\"Map<String, List<Optional<com.example.Bean>>> values\">var v = [[values]];</script>")
        .compile().assertSuccess();
    assertEquals("<script>var v = ({\"key\":[{\"name\":\"ok\"}]});</script>",
        c.render("page", model("values", Map.of("key", List.of(Optional.of(c.newInstance("com.example.Bean")))))));
  }
}
