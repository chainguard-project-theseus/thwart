package dev.chainguard.thwart.compiler.types;

import static dev.chainguard.thwart.compiler.types.TypesHarness.call;
import static dev.chainguard.thwart.compiler.types.TypesHarness.fn;
import static dev.chainguard.thwart.compiler.types.TypesHarness.idx;
import static dev.chainguard.thwart.compiler.types.TypesHarness.lit;
import static dev.chainguard.thwart.compiler.types.TypesHarness.prop;
import static dev.chainguard.thwart.compiler.types.TypesHarness.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionRegistryTest {
  private static final String FMT =
      """
      package com.example;
      import dev.chainguard.thwart.runtime.*;
      @TemplateFunctions("fmt")
      public final class Fmt {
        private Fmt() {}
        @TemplateFunction public static String upper(String s) { return s == null ? null : s.toUpperCase(); }
        @TemplateFunction public static String twice(int x) { return "int"; }
        @TemplateFunction public static String twice(long x) { return "long"; }
        @TemplateFunction public static String twice(Integer x) { return "Integer"; }
        @TemplateFunction public static String join(String sep, String... parts) { return String.join(sep, parts); }
        @TemplateFunction public static String describe(Object o) { return "obj"; }
        @TemplateFunction public static int count(java.util.Collection<?> c) { return c.size(); }
        @TemplateFunction public static java.util.List<Item> items(int n) {
          return java.util.Collections.nCopies(n, new Item("i", 1, null));
        }
        public static String notAnnotated(String s) { return s; }
      }
      """;

  private static TypesHarness fmt() {
    return TypesHarness.create()
        .java("com.example.Fmt", FMT)
        .functions("com.example.Fmt")
        .local("p", "com.example.Pojo", "new com.example.Pojo()")
        .local("items", "java.util.List<com.example.Item>", "java.util.List.of()")
        .local("n", "java.lang.Long", "5L")
        .local("s", "java.lang.String", "\"ann\"");
  }

  @Test
  void callsRegisteredFunctions() {
    TypesHarness h = fmt();
    assertEquals("ANN", h.eval(call(v("fmt"), "upper", v("s"))));
    assertEquals("ANN", h.eval(fn("fmt", "upper", v("s"))));
    assertEquals("com.example.Fmt.upper(s)", h.compile(fn("fmt", "upper", v("s"))).code());
    assertEquals("a, b", h.eval(fn("fmt", "join", lit(", "), lit("a"), lit("b"))));
    assertEquals("", h.eval(fn("fmt", "join", lit("-"))));
    assertEquals("obj", h.eval(fn("fmt", "describe", v("p"))));
    assertEquals("obj", h.eval(fn("fmt", "describe", lit(1))));
    assertEquals(0, h.eval(fn("fmt", "count", v("items"))));
    assertEquals("i", h.eval(prop(idx(fn("fmt", "items", lit(2)), lit(1)), "name")));
  }

  @Test
  void overloadResolutionFollowsJava() {
    TypesHarness h = fmt().local("boxed", "java.lang.Integer", "1");
    assertEquals("int", h.eval(fn("fmt", "twice", lit(1))));
    assertEquals("long", h.eval(fn("fmt", "twice", lit(1L))));
    assertEquals("Integer", h.eval(fn("fmt", "twice", v("boxed"))));
    assertEquals("long", h.eval(fn("fmt", "twice", v("n"))));
    h.compile(fn("fmt", "upper", lit(1))).assertError("no single applicable fmt.upper for arguments (int)");
  }

  @Test
  void unknownFunctionsAndNamespaces() {
    TypesHarness h = fmt();
    h.compile(fn("fmt", "notAnnotated", lit("x"))).assertError("unknown function fmt.notAnnotated", "@TemplateFunction");
    h.compile(call(v("fmt"), "nope")).assertError("unknown function fmt.nope");
    h.compile(fn("nope", "f"))
        .assertError(
            "unknown function namespace 'nope': register a class annotated @TemplateFunctions(\"nope\")"
                + " (built-ins: strings, numbers, temporals, lists, css, html; auth with the Spring starter)");
    h.compile(prop(v("fmt"), "upper")).assertError("'fmt.upper' is a function");
    h.compile(v("fmt")).assertError("'fmt' is a function namespace");
  }

  @Test
  void variablesShadowNamespaces() {
    TypesHarness h = fmt().local("fmt", "com.example.Item", "new com.example.Item(\"shadow\", 1, null)");
    assertEquals("shadow", h.eval(prop(v("fmt"), "name")));
  }

  @Test
  void builtinNamespacesAreRegistered() {
    fmt()
        .run(
            f -> {
              // NAMES is derived from CLASSES; this pins each derived name to the class's annotation.
              assertTrue(f.compiler().functions().namespaces().contains("fmt"));
              assertEquals(List.of("strings", "numbers", "temporals", "lists", "css", "html"), BuiltinNamespaces.NAMES);
              assertTrue(f.compiler().functions().namespaces().containsAll(BuiltinNamespaces.NAMES), f.compiler().functions().namespaces().toString());
              assertTrue(f.errors().isEmpty(), f.errors().toString());
            });
  }

  @Test
  void invalidFunctionsAreBuildErrors() {
    String bad =
        """
        package com.example;
        import dev.chainguard.thwart.runtime.*;
        @TemplateFunctions("bad")
        public final class Bad {
          @TemplateFunction public String inst() { return ""; }
          @TemplateFunction static String hidden() { return ""; }
          @TemplateFunction public static void nothing() {}
          @TemplateFunction public static Object obj() { return null; }
          @TemplateFunction public static java.util.List<Object> objs() { return null; }
          @TemplateFunction public static <T> T generic(java.util.List<T> l) { return null; }
          @TemplateFunction public static String ok(Object o, java.util.List<?> l) { return "ok"; }
        }
        """;
    TypesHarness h = TypesHarness.create().java("com.example.Bad", bad).functions("com.example.Bad");
    h.run(
        f -> {
          f.compiler().functions();
          String all = String.join("\n", f.errors());
          assertTrue(all.contains("com/example/Bad.java:1:1: error: @TemplateFunction com.example.Bad.inst must be public static"), all);
          assertTrue(all.contains("com.example.Bad.hidden must be public static"), all);
          assertTrue(all.contains("com.example.Bad.nothing must return a value"), all);
          assertTrue(all.contains("com.example.Bad.obj returns java.lang.Object"), all);
          assertTrue(all.contains("com.example.Bad.objs returns java.util.List<java.lang.Object>"), all);
          assertTrue(all.contains("com.example.Bad.generic returns T"), all);
          assertEquals(6, f.errors().size(), all);
        });
    h.compile(fn("bad", "inst")).assertError("unknown function bad.inst");
    h.compile(fn("bad", "obj")).assertError("unknown function bad.obj");
    assertEquals("ok", h.eval(fn("bad", "ok", lit("x"), lit(null))));
  }

  @Test
  void denyListedTypesInSignaturesAreBuildErrors() {
    StringBuilder src = new StringBuilder("package com.example; import dev.chainguard.thwart.runtime.*; @TemplateFunctions(\"deny\") public final class Deny {\n");
    List<String> denied = new ArrayList<>(ViewSafetyTest.DENIED);
    denied.remove("java.lang.Object"); // allowed as a parameter; covered as a return type above
    for (int i = 0; i < denied.size(); i++) {
      String t = denied.get(i);
      src.append("@TemplateFunction public static String p").append(i).append('(').append(t).append(" x) { return null; }\n");
      src.append("@TemplateFunction public static ").append(t).append(" r").append(i).append("() { return null; }\n");
    }
    src.append("}");
    TypesHarness h = TypesHarness.create().java("com.example.Deny", src.toString()).functions("com.example.Deny");
    ViewSafetyTest.HOSTILE.forEach(h::java);
    h.run(
        f -> {
          f.compiler().functions();
          String all = String.join("\n", f.errors());
          for (int i = 0; i < denied.size(); i++) {
            assertTrue(all.contains("com.example.Deny.p" + i + " parameter x"), denied.get(i) + "\n" + all);
            assertTrue(all.contains("com.example.Deny.r" + i + " returns"), denied.get(i) + "\n" + all);
          }
          assertEquals(2 * denied.size(), f.errors().size(), all);
          assertTrue(f.compiler().functions().overloads("deny", "p0").isEmpty());
        });
  }

  @Test
  void duplicateAndReservedNamespaces() {
    String a = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"dup\") public final class DupA {}";
    String b = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"dup\") public final class DupB {}";
    String strings = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"strings\") public final class MyStrings {}";
    String fields = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"fields\") public final class Fields {}";
    String hidden = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"hidden\") final class Hidden {}";
    String ids = "package com.example; @dev.chainguard.thwart.runtime.TemplateFunctions(\"ids\") public final class Ids {}";
    TypesHarness.create()
        .java("com.example.DupA", a)
        .java("com.example.DupB", b)
        .java("com.example.MyStrings", strings)
        .java("com.example.Fields", fields)
        .java("com.example.Hidden", hidden)
        .java("com.example.Ids", ids)
        .functions("com.example.DupA")
        .functions("com.example.DupB")
        .functions("com.example.MyStrings")
        .functions("com.example.Fields")
        .functions("com.example.Hidden")
        .functions("com.example.Ids")
        .run(
            f -> {
              f.compiler().functions();
              String all = String.join("\n", f.errors());
              assertTrue(all.contains("duplicate function namespace 'dup': declared by com.example.DupA and com.example.DupB"), all);
              assertTrue(
                  all.contains("duplicate function namespace 'strings': declared by com.example.MyStrings and dev.chainguard.thwart.runtime.internal.fn.Strings"),
                  all);
              assertTrue(all.contains("function namespace 'fields' on com.example.Fields is reserved for form fields"), all);
              assertTrue(all.contains("@TemplateFunctions class com.example.Hidden must be public"), all);
              assertTrue(all.contains("function namespace 'ids' on com.example.Ids is reserved for element ids"), all);
              assertEquals(5, f.errors().size(), all);
            });
  }
}
