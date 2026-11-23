package dev.chainguard.thwart.compiler.types;

import static dev.chainguard.thwart.compiler.types.TypesHarness.prop;
import static dev.chainguard.thwart.compiler.types.TypesHarness.ref;
import static dev.chainguard.thwart.compiler.types.TypesHarness.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.Test;

/** The view-safe allow-list, the never-view-safe deny-list, and parameter type resolution. */
class ViewSafetyTest {

  /** Stand-ins for servlet/Spring types (not on the test classpath) plus hostile view types. */
  static final Map<String, String> HOSTILE =
      Map.ofEntries(
          Map.entry("jakarta.servlet.ServletContext", "package jakarta.servlet; public interface ServletContext {}"),
          Map.entry("javax.servlet.http.HttpSession", "package javax.servlet.http; public interface HttpSession {}"),
          Map.entry(
              "org.springframework.context.ApplicationContext",
              "package org.springframework.context; public interface ApplicationContext {}"),
          Map.entry(
              "org.springframework.beans.factory.BeanFactory",
              "package org.springframework.beans.factory; public interface BeanFactory {}"),
          Map.entry("org.springframework.core.env.Environment", "package org.springframework.core.env; public interface Environment {}"),
          Map.entry("org.springframework.core.io.Resource", "package org.springframework.core.io; public interface Resource {}"),
          Map.entry(
              "org.springframework.web.context.WebApplicationContext",
              "package org.springframework.web.context; public interface WebApplicationContext {}"),
          Map.entry(
              "org.springframework.expression.ExpressionParser",
              "package org.springframework.expression; public interface ExpressionParser {}"),
          Map.entry(
              "com.example.EvilThread",
              "package com.example; @dev.chainguard.thwart.runtime.TemplateView public class EvilThread extends Thread {}"),
          Map.entry(
              "com.example.EvilContext",
              "package com.example; @dev.chainguard.thwart.runtime.TemplateView public record EvilContext(String name)"
                  + " implements org.springframework.context.ApplicationContext {}"),
          Map.entry("com.example.Holder", "package com.example; public record Holder(Thread thread, String name) {}"),
          Map.entry("com.example.Box", "package com.example; public record Box<T>(T value) {}"),
          Map.entry("com.other.Item", "package com.other; public record Item(String id) {}"));

  /** Every deny-list entry, as a type that javac can resolve. */
  static final List<String> DENIED =
      List.of(
          "java.lang.Object",
          "java.lang.Class<java.lang.String>",
          "java.lang.ClassLoader",
          "java.lang.reflect.Method",
          "java.lang.reflect.Field",
          "java.lang.invoke.MethodHandle",
          "java.lang.invoke.MethodHandles.Lookup",
          "java.lang.Thread",
          "java.lang.ThreadGroup",
          "java.lang.Runtime",
          "java.lang.Process",
          "java.lang.ProcessBuilder",
          "java.lang.ProcessHandle",
          "java.lang.System",
          "java.lang.SecurityManager",
          "java.lang.Module",
          "java.lang.ModuleLayer",
          "java.io.File",
          "java.nio.file.Path",
          "java.nio.file.Files",
          "javax.script.ScriptEngine",
          "jakarta.servlet.ServletContext",
          "javax.servlet.http.HttpSession",
          "org.springframework.context.ApplicationContext",
          "org.springframework.beans.factory.BeanFactory",
          "org.springframework.core.env.Environment",
          "org.springframework.core.io.Resource",
          "org.springframework.web.context.WebApplicationContext",
          "org.springframework.expression.ExpressionParser",
          // Deny-listed supertypes, even when annotated @TemplateView or a record.
          "com.example.EvilThread",
          "com.example.EvilContext",
          // Deny-listed type arguments and array components.
          "java.util.List<java.lang.Thread>",
          "java.util.Map<java.lang.String,java.lang.Class<java.lang.String>>",
          "java.util.Optional<java.lang.reflect.Method>",
          "java.util.ArrayList<java.lang.Thread>",
          "java.util.TreeMap<java.lang.String,java.lang.Class<java.lang.String>>",
          "java.lang.Thread[]",
          "com.example.Box<java.lang.Runtime>");

  private static TypesHarness harness() {
    TypesHarness h = TypesHarness.create();
    HOSTILE.forEach(h::java);
    return h;
  }

  @Test
  void denyListIsNeverViewSafe() {
    harness()
        .run(
            f -> {
              ViewSafety vs = f.safety();
              for (String name : DENIED) {
                TypeMirror t = f.type(name);
                assertTrue(vs.neverViewSafeReason(t).isPresent(), name);
                assertTrue(vs.neverViewSafeReason(t).get().contains("never view-safe"), vs.neverViewSafeReason(t).get());
                assertFalse(vs.isViewSafe(t), name);
              }
              assertEquals(
                  "com.example.EvilThread is never view-safe (it is a java.lang.Thread)",
                  vs.neverViewSafeReason(f.type("com.example.EvilThread")).get());
            });
  }

  @Test
  void denyListParametersAreRejected() {
    harness()
        .run(
            f -> {
              for (String name : DENIED) {
                int before = f.errors().size();
                assertNull(f.compiler().resolveType(ref(name), List.of()), name);
                assertEquals(before + 1, f.errors().size(), name);
                String err = f.errors().get(before);
                assertTrue(err.startsWith("t.html:1:1: error: parameter type"), err);
                assertTrue(err.contains("never view-safe"), err);
              }
              // Raw Class is rejected as never view-safe, not just as raw.
              assertNull(f.compiler().resolveType(ref("Class"), List.of()));
              assertTrue(f.errors().getLast().contains("java.lang.Class is never view-safe"), f.errors().getLast());
            });
  }

  @Test
  void allowList() {
    harness()
        .run(
            f -> {
              ViewSafety vs = f.safety();
              for (String name :
                  List.of(
                      "int",
                      "boolean",
                      "java.lang.Integer",
                      "java.lang.String",
                      "java.lang.CharSequence",
                      "com.example.Status",
                      "com.example.Item",
                      "com.example.Account",
                      "java.time.LocalDate",
                      "java.time.Duration",
                      "java.math.BigDecimal",
                      "java.math.BigInteger",
                      "java.util.UUID",
                      "dev.chainguard.thwart.runtime.LoopStatus",
                      "dev.chainguard.thwart.runtime.SafeHtml",
                      "java.util.List<com.example.Item>",
                      "java.util.Set<java.lang.String>",
                      "java.util.Collection<java.lang.Long>",
                      "java.lang.Iterable<com.example.Status>",
                      "java.util.Map<java.lang.String,java.util.List<com.example.Item>>",
                      "java.util.Map.Entry<java.lang.String,java.lang.Integer>",
                      "java.util.Optional<java.lang.String>",
                      // java.util implementations and sub-interfaces, seen as their interface (#135).
                      "java.util.ArrayList<java.lang.String>",
                      "java.util.LinkedList<com.example.Item>",
                      "java.util.HashSet<java.lang.String>",
                      "java.util.TreeSet<java.lang.String>",
                      "java.util.SortedSet<java.lang.String>",
                      "java.util.LinkedHashMap<java.lang.String,com.example.Item>",
                      "java.util.TreeMap<java.lang.String,java.util.List<com.example.Item>>",
                      "java.util.SortedMap<java.lang.String,java.lang.Integer>",
                      "java.util.NavigableMap<java.lang.String,java.lang.Integer>",
                      "java.util.EnumMap<com.example.Status,java.lang.Integer>",
                      "java.util.ArrayDeque<java.lang.String>",
                      "java.util.AbstractMap.SimpleEntry<java.lang.String,java.lang.Integer>",
                      "com.example.Item[]",
                      "int[]",
                      "com.example.Holder")) {
                assertTrue(vs.isViewSafe(f.type(name)), name);
              }
              for (String name :
                  List.of(
                      "com.example.Pojo",
                      "java.util.List<com.example.Pojo>",
                      "java.util.List",
                      "java.util.ArrayList",
                      "java.util.ArrayList<com.example.Pojo>",
                      "java.util.TreeMap<java.lang.String,com.example.Pojo>",
                      // Map<Object, Object>; Iterable only (iterating it loads classes); not java.util.
                      "java.util.Properties",
                      "java.util.ServiceLoader<java.lang.String>",
                      "java.util.Iterator<java.lang.String>",
                      "java.util.concurrent.ConcurrentHashMap<java.lang.String,java.lang.String>",
                      "java.time.Clock",
                      "java.time.InstantSource",
                      "java.time.DateTimeException",
                      "java.lang.Runnable",
                      "dev.chainguard.thwart.runtime.Fragment")) {
                TypeMirror t = f.type(name);
                assertFalse(vs.isViewSafe(t), name);
                assertTrue(vs.neverViewSafeReason(t).isEmpty(), name);
              }
            });
  }

  @Test
  void javaUtilContainerNamesItsBadTypeArgument() {
    harness()
        .run(
            f -> {
              ViewSafety vs = f.safety();
              assertEquals(
                  "its type argument com.example.Pojo is not a record or annotated @TemplateView",
                  vs.badTypeArgument(f.type("java.util.TreeMap<java.lang.String,com.example.Pojo>")).get());
              assertEquals(
                  "java.util.List<java.lang.String>", vs.containerView(f.type("java.util.ArrayList<java.lang.String>")).toString());
              assertEquals(
                  "java.util.Map<java.lang.String,java.lang.Integer>",
                  vs.containerView(f.type("java.util.TreeMap<java.lang.String,java.lang.Integer>")).toString());
              assertEquals(
                  "java.util.Collection<java.lang.String>",
                  vs.containerView(f.type("java.util.ArrayDeque<java.lang.String>")).toString());
              assertNull(f.compiler().resolveType(ref("ArrayList<Pojo>"), List.of("com.example.*")));
              String err = f.errors().getLast();
              assertTrue(err.startsWith("t.html:1:1: error: parameter type"), err);
              assertTrue(err.contains("its type argument com.example.Pojo"), err);
            });
  }

  /** #913: a UUID prints, fills links and serializes as its toString(), and offers only its getters. */
  @Test
  void uuidIsAPrintableValue() {
    String ns = "xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"com.example.*\" ";
    Compilation.Builder b =
        Compilation.builder()
            .java("com.example.A", "package com.example; public record A(java.util.UUID id, java.util.List<java.util.UUID> ids) {}");
    Compilation c =
        b.template(
                "a.html",
                "<div " + ns + "t:params=\"A a, UUID id\"><p t:text=\"id\" t:title=\"a.id\">x</p>"
                    + "<a t:href=\"@{/x/{id}(id=a.id, q=id, all=a.ids)}\">x</a><i t:each=\"u : a.ids\">[[${u}]]</i>"
                    + "<b t:text=\"a.id.mostSignificantBits\">0</b>"
                    + "<script>const ids = [[${a.ids}]];</script></div>")
            .compile()
            .assertSuccess();
    java.util.UUID id = java.util.UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    Object a = c.newInstance("com.example.A", id, List.of(id));
    assertEquals(
        "<div><p title=\"123e4567-e89b-42d3-a456-426614174000\">123e4567-e89b-42d3-a456-426614174000</p>"
            + "<a href=\"/x/123e4567-e89b-42d3-a456-426614174000?q=123e4567-e89b-42d3-a456-426614174000"
            + "&amp;all=123e4567-e89b-42d3-a456-426614174000\">x</a><i>123e4567-e89b-42d3-a456-426614174000</i>"
            + "<b>" + id.getMostSignificantBits() + "</b>"
            + "<script>const ids = ([\"123e4567-e89b-42d3-a456-426614174000\"]);</script></div>",
        c.render("a", Map.of("a", a, "id", id)));

    // Only getters: no version(), timestamp() or compareTo; and not framework-neutral.
    for (String expr : List.of("id.version()", "id.timestamp()", "id.compareTo(id)", "id.toString()")) {
      b.template("a.html", "<p " + ns + "t:params=\"UUID id\" t:text=\"" + expr + "\">x</p>").compile().assertError("a.html:1:");
    }
    b.template("a.html", "<p " + ns + "t:params=\"UUID id\" t:data-id=\"id\">x</p>")
        .compile()
        .assertError("a.html:1:", "framework-neutral");
  }

  @Test
  void dateParameterErrorNamesTheAcceptedTypes() {
    harness()
        .run(
            f -> {
              assertNull(f.compiler().resolveType(ref("java.util.Date"), List.of()));
              String err = f.errors().getLast();
              assertTrue(err.contains("java.time value, a UUID"), err);
              assertFalse(err.contains("JDK value type"), err);
            });
  }

  @Test
  void dateTimeExceptionParameterIsRejected() {
    harness()
        .run(
            f -> {
              assertNull(f.compiler().resolveType(ref("java.time.DateTimeException"), List.of()));
              String err = f.errors().getLast();
              assertTrue(err.startsWith("t.html:1:1: error: parameter type"), err);
              assertTrue(err.contains("java.time.DateTimeException"), err);
            });
  }

  @Test
  void neverViewSafeRecordComponentIsUnreachable() {
    TypesHarness h = harness().local("h", "com.example.Holder", "new com.example.Holder(null, \"n\")");
    assertEquals("n", h.eval(prop(v("h"), "name")));
    TypesHarness.Result r = h.compile(prop(v("h"), "thread"));
    assertNull(r.code());
    assertTrue(r.errors().get(0).contains("java.lang.Thread is never view-safe"), r.errors().toString());
  }

  @Test
  void resolveTypeWithImportsImplicitsAndGenerics() {
    harness()
        .run(
            f -> {
              ExpressionCompiler ec = f.compiler();
              List<String> wildcard = List.of("com.example.*");
              assertEquals("com.example.Item", ec.resolveType(ref("Item"), wildcard).toString());
              assertEquals("com.example.Item", ec.resolveType(ref("Item"), List.of(" com.example.Item ")).toString());
              assertEquals("com.other.Item", ec.resolveType(ref("Item"), List.of("com.other.Item", "com.example.*")).toString());
              assertEquals("com.example.User", ec.resolveType(ref("com.example.User"), List.of()).toString());
              assertEquals("java.util.List<com.example.Item>", ec.resolveType(ref("List<Item>"), wildcard).toString());
              assertEquals(
                  "java.util.Map<java.lang.String,java.util.List<com.example.Item>>",
                  ec.resolveType(ref("Map<String, List<Item>>"), wildcard).toString());
              assertEquals(
                  "java.util.Map.Entry<java.lang.String,java.lang.Integer>",
                  ec.resolveType(ref("Map.Entry<String,Integer>"), List.of()).toString());
              assertEquals("java.time.LocalDate", ec.resolveType(ref("LocalDate"), List.of()).toString());
              assertEquals("java.math.BigDecimal", ec.resolveType(ref("BigDecimal"), List.of()).toString());
              assertEquals("dev.chainguard.thwart.runtime.LoopStatus", ec.resolveType(ref("LoopStatus"), List.of()).toString());
              assertEquals("dev.chainguard.thwart.runtime.SafeHtml", ec.resolveType(ref("SafeHtml"), List.of()).toString());
              assertEquals("dev.chainguard.thwart.runtime.Fragment", ec.resolveType(ref("Fragment"), List.of()).toString());
              assertEquals("int", ec.resolveType(ref("int"), List.of()).toString());
              assertEquals("int[][]", ec.resolveType(ref("int[][]"), List.of()).toString());
              assertEquals("com.example.Item[]", ec.resolveType(ref("Item[]"), wildcard).toString());
              assertEquals("com.example.Box<com.example.Item>", ec.resolveType(ref("Box<Item>"), wildcard).toString());
              assertTrue(f.errors().isEmpty(), f.errors().toString());
            });
  }

  @Test
  void resolveTypeErrors() {
    harness()
        .run(
            f -> {
              ExpressionCompiler ec = f.compiler();
              List<String> wildcard = List.of("com.example.*");
              assertNull(ec.resolveType(ref("Nope"), wildcard));
              assertNull(ec.resolveType(ref("com.example.Nope"), wildcard));
              assertNull(ec.resolveType(ref("Missing"), List.of("com.example.Item", "com.example.Missing")));
              assertNull(ec.resolveType(ref("Item"), List.of("com.example.*", "com.other.*")));
              assertNull(ec.resolveType(ref("Item"), List.of()));
              assertNull(ec.resolveType(ref("List"), List.of()));
              assertNull(ec.resolveType(ref("List<String,String>"), List.of()));
              assertNull(ec.resolveType(ref("List<int>"), List.of()));
              assertNull(ec.resolveType(ref("int<String>"), List.of()));
              assertNull(ec.resolveType(ref("Pojo"), wildcard));
              assertNull(ec.resolveType(ref("List<Pojo>"), wildcard));
              List<String> errors = f.errors();
              String all = String.join("\n", errors);
              assertEquals(11, errors.size(), all);
              assertTrue(errors.get(0).contains("unknown type 'Nope'"), all);
              assertTrue(errors.get(1).contains("unknown type 'com.example.Nope'"), all);
              assertTrue(errors.get(2).contains("unknown type 'com.example.Missing' in t:import"), all);
              assertTrue(errors.get(3).contains("'Item' is ambiguous"), all);
              assertTrue(errors.get(4).contains("unknown type 'Item'"), all);
              assertTrue(errors.get(5).contains("List needs 1 type argument"), all);
              assertTrue(errors.get(6).contains("takes 1 type argument(s), got 2"), all);
              assertTrue(errors.get(7).contains("use java.lang.Integer, not int"), all);
              assertTrue(errors.get(8).contains("int cannot have type arguments"), all);
              assertTrue(errors.get(9).contains("com.example.Pojo is not view-safe"), all);
              assertTrue(errors.get(10).contains("is not view-safe"), all);
              assertTrue(errors.stream().allMatch(e -> e.startsWith("t.html:1:1: error: ")), all);
            });
  }

  @Test
  void genericRecordComponentResolvesThroughTypeArguments() {
    TypesHarness h =
        harness()
            .local(
                "box",
                "com.example.Box<com.example.Item>",
                "new com.example.Box<>(new com.example.Item(\"pen\", 1, null))");
    TypesHarness.Result r = h.compile(prop(prop(v("box"), "value"), "name"));
    assertNotNull(r.code(), r.errors().toString());
    assertEquals("pen", h.evalCode(r));
  }
  /** A container that isn't view-safe names the type argument that isn't, and why (#260). */
  private static Compilation entries(String mapType, String body) {
    return Compilation.builder()
        .java("com.example.Hidden", "package com.example; public class Hidden { public String getId() { return \"h\"; } }")
        .java("com.example.Pojo", "package com.example; public class Pojo { public String getName() { return \"p\"; } }")
        .java(
            "com.example.Holder",
            "package com.example; public record Holder(" + mapType + " map, java.util.List<Pojo> pojos) {}")
        .template("page.html", "<div t:params=\"com.example.Holder h\">\n" + body + "</div>")
        .compile();
  }

  @Test
  void containerNamesTheTypeArgumentThatIsNotViewSafe() {
    Compilation c = entries("java.util.Map<Hidden, String>", "<p t:each=\"e : h.map\">[[e.key]]</p>");
    c.assertError("page.html:2:25: ", "cannot read 'key'", "is not view-safe: its type argument com.example.Hidden is not"
        + " a record or annotated @TemplateView");
    // The container itself is view-safe: don't tell the user to annotate Map.Entry.
    assertFalse(String.join("\n", c.errors()).contains("make it a record"), c.errors().toString());
  }

  @Test
  void nestedTypeArgumentIsFound() {
    entries(
            "java.util.Map<String, java.util.Map<Hidden, java.util.List<String>>>",
            "<p t:each=\"e : h.map\">[[e.key]]</p>")
        .assertError("page.html:2:", "cannot read 'key'", "its type argument com.example.Hidden is not a record");
    entries("java.util.Map<String, String>", "<p t:each=\"p : h.pojos\">[[p.name]]</p><i>[[h.pojos]]</i>")
        .assertError("page.html:2:", "com.example.Pojo is not view-safe (make it a record or annotate it @TemplateView)")
        .assertError(
            "page.html:2:",
            "cannot output a value of type java.util.List<com.example.Pojo>: it is not view-safe: its type argument"
                + " com.example.Pojo is not a record or annotated @TemplateView");
  }

  @Test
  void parameterTypeNamesTheTypeArgument() {
    Compilation.builder()
        .java("com.example.Pojo", "package com.example; public class Pojo {}")
        .template("page.html", "<p t:params=\"java.util.Map<String, java.util.List<com.example.Pojo[]>> m\">x</p>")
        .compile()
        .assertError(
            "page.html:1:",
            "is not view-safe: its type argument com.example.Pojo is not a record or annotated @TemplateView");
  }
}
