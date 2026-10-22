package dev.tessera.compiler.types;

import static dev.tessera.compiler.types.TypesHarness.prop;
import static dev.tessera.compiler.types.TypesHarness.ref;
import static dev.tessera.compiler.types.TypesHarness.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
              "package com.example; @dev.tessera.runtime.TemplateView public class EvilThread extends Thread {}"),
          Map.entry(
              "com.example.EvilContext",
              "package com.example; @dev.tessera.runtime.TemplateView public record EvilContext(String name)"
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
                      "dev.tessera.runtime.LoopStatus",
                      "dev.tessera.runtime.SafeHtml",
                      "dev.tessera.runtime.TrustedUrl",
                      "java.util.List<com.example.Item>",
                      "java.util.Set<java.lang.String>",
                      "java.util.Collection<java.lang.Long>",
                      "java.lang.Iterable<com.example.Status>",
                      "java.util.Map<java.lang.String,java.util.List<com.example.Item>>",
                      "java.util.Map.Entry<java.lang.String,java.lang.Integer>",
                      "java.util.Optional<java.lang.String>",
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
                      "java.util.ArrayList<java.lang.String>",
                      "java.time.Clock",
                      "java.time.InstantSource",
                      "java.time.DateTimeException",
                      "java.lang.Runnable",
                      "dev.tessera.runtime.Fragment")) {
                TypeMirror t = f.type(name);
                assertFalse(vs.isViewSafe(t), name);
                assertTrue(vs.neverViewSafeReason(t).isEmpty(), name);
              }
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
              assertEquals("dev.tessera.runtime.LoopStatus", ec.resolveType(ref("LoopStatus"), List.of()).toString());
              assertEquals("dev.tessera.runtime.SafeHtml", ec.resolveType(ref("SafeHtml"), List.of()).toString());
              assertEquals("dev.tessera.runtime.TrustedUrl", ec.resolveType(ref("TrustedUrl"), List.of()).toString());
              assertEquals("dev.tessera.runtime.Fragment", ec.resolveType(ref("Fragment"), List.of()).toString());
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
}
