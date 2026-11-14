package dev.chainguard.thwart.compiler.security;

import static org.junit.jupiter.api.Assertions.assertNull;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Gadget types (reflection, class loading, processes, servlet and Spring context objects) can
 * never reach a template: not as a parameter, not through a getter, a container, a supertype,
 * an annotation or a registered function. Spring and servlet types are stand-ins compiled in the
 * same unit, since they are not on the compiler's test classpath.
 */
class GadgetTypeTest {

  private static Compilation.Builder sources() {
    return Compilation.builder()
        .java(
            "org.springframework.core.io.FileSystemResource",
            "package org.springframework.core.io; @dev.chainguard.thwart.runtime.TemplateView"
                + " public class FileSystemResource { public String getPath() { return \"\"; } }")
        .java(
            "org.springframework.context.ApplicationContext",
            "package org.springframework.context; public interface ApplicationContext {}")
        .java(
            "org.springframework.core.env.Environment",
            "package org.springframework.core.env; public interface Environment {}")
        .java(
            "org.springframework.beans.factory.BeanFactory",
            "package org.springframework.beans.factory; public interface BeanFactory {}")
        .java(
            "org.springframework.expression.spel.standard.SpelExpressionParser",
            "package org.springframework.expression.spel.standard; public class SpelExpressionParser {}")
        .java(
            "org.springframework.web.context.WebApplicationContext",
            "package org.springframework.web.context; public interface WebApplicationContext {}")
        .java(
            "jakarta.servlet.http.HttpServletRequest",
            "package jakarta.servlet.http; public interface HttpServletRequest { String getParameter(String n); }")
        .java("javax.servlet.ServletContext", "package javax.servlet; public interface ServletContext {}")
        .java(
            "com.example.Gadget",
            """
            package com.example;
            import java.util.*;
            @dev.chainguard.thwart.runtime.TemplateView
            public class Gadget {
              public String getName() { return "g"; }
              public ClassLoader getLoader() { return null; }
              public Runtime getRuntime() { return null; }
              public Class<?> getType() { return null; }
              public Object getAny() { return null; }
              public Thread getThread() { return null; }
              public List<Thread> getThreads() { return null; }
              public Map<String, ClassLoader> getLoaders() { return null; }
              public Optional<ProcessBuilder> getBuilder() { return Optional.empty(); }
              public java.lang.reflect.Method[] getMethods() { return null; }
              public java.io.File getFile() { return null; }
              public java.nio.file.Path getPath() { return null; }
              public org.springframework.context.ApplicationContext getContext() { return null; }
              public org.springframework.core.io.FileSystemResource getResource() { return null; }
              public jakarta.servlet.http.HttpServletRequest getRequest() { return null; }
              public Threads getThreadList() { return null; }
            }
            """)
        .java(
            "com.example.Threads",
            "package com.example; @dev.chainguard.thwart.runtime.TemplateView public class Threads extends java.util.ArrayList<Thread> {}")
        .java(
            "com.example.EvilThread",
            "package com.example; @dev.chainguard.thwart.runtime.TemplateView public class EvilThread extends Thread {}")
        .java(
            "com.example.EvilRequest",
            "package com.example; @dev.chainguard.thwart.runtime.TemplateView public record EvilRequest(String name)"
                + " implements jakarta.servlet.http.HttpServletRequest {"
                + " public String getParameter(String n) { return n; } }")
        .java("com.example.Holder", "package com.example; public record Holder(Class<?> type, String name) {}")
        .java("com.example.Box", "package com.example; public record Box<T>(T value) {}");
  }

  private static void assertRejected(String params, String body, String... fragments) {
    Compilation c =
        sources()
            .template(
                "page.html",
                "<div t:import=\"com.example.*\" t:params=\"" + params + "\">\n" + body + "\n</div>")
            .compile();
    String[] all = new String[fragments.length + 1];
    all[0] = "page.html:";
    System.arraycopy(fragments, 0, all, 1, fragments.length);
    c.assertError(all);
    assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
  }

  static final List<String> GADGET_PARAMS =
      List.of(
          "Object o",
          "Class<?> o",
          "Class<String> o",
          "Class[] o",
          "ClassLoader o",
          "Runtime o",
          "System o",
          "Thread o",
          "ThreadGroup o",
          "Process o",
          "ProcessBuilder o",
          "ProcessHandle o",
          "Module o",
          "ModuleLayer o",
          "java.lang.reflect.Method o",
          "java.lang.reflect.Field o",
          "java.lang.reflect.Constructor<?> o",
          "java.lang.invoke.MethodHandle o",
          "java.lang.invoke.MethodHandles.Lookup o",
          "java.io.File o",
          "java.nio.file.Path o",
          "javax.script.ScriptEngine o",
          "javax.script.ScriptEngineManager o",
          "List<Class<?>> o",
          "List<Object> o",
          "Map<String, Thread> o",
          "Map<ClassLoader, String> o",
          "Optional<Runtime> o",
          "Iterable<java.lang.reflect.Method> o",
          "Map.Entry<String, ProcessBuilder> o",
          "Box<ClassLoader> o",
          "EvilThread o",
          "EvilRequest o",
          "Gadget o, java.util.function.Supplier<String> f",
          "org.springframework.core.io.FileSystemResource o",
          "org.springframework.context.ApplicationContext o",
          "org.springframework.core.env.Environment o",
          "org.springframework.beans.factory.BeanFactory o",
          "org.springframework.expression.spel.standard.SpelExpressionParser o",
          "org.springframework.web.context.WebApplicationContext o",
          "jakarta.servlet.http.HttpServletRequest o",
          "javax.servlet.ServletContext o");

  @ParameterizedTest
  @FieldSource("GADGET_PARAMS")
  void gadgetParameterTypesAreRejected(String params) {
    assertRejected(params, "<p>x</p>");
  }

  static final List<String> GADGET_PATHS =
      List.of(
          "g.loader",
          "g.getLoader()",
          "g.runtime",
          "g.type",
          "g.type.name",
          "g.any",
          "g.thread",
          "g.threads",
          "g.threads[0]",
          "g.threads.first",
          "g.threads.getFirst()",
          "g.loaders['x']",
          "g.builder",
          "g.methods",
          "g.methods[0].invoke(null)",
          "g.file",
          "g.path",
          "g.context",
          "g.resource",
          "g.resource.path",
          "g.request",
          "g.request.getParameter('x')",
          "g.threadList[0]",
          "g.threadList.first",
          "g.threadList.last",
          "g.threadList.getLast()",
          "g.getClass()",
          "g.name.getClass()",
          "#strings.class",
          "strings.getClass()");

  @ParameterizedTest
  @FieldSource("GADGET_PATHS")
  void gadgetsAreUnreachableThroughExpressions(String expr) {
    assertRejected("Gadget g", "<p t:text=\"" + expr + "\">x</p>");
    assertRejected("Gadget g", "<i t:each=\"v : " + expr + "\">[[v]]</i>");
    assertRejected("Gadget g", "<i t:with=\"v=" + expr + "\">x</i>");
  }

  @Test
  void containerSubclassOfGadgetsCannotYieldElements() {
    // class Threads extends ArrayList<Thread>: the declaration is accepted (its own methods are
    // unreachable), but every way of getting at an element re-checks the element type.
    assertRejected("Threads t", "<p t:text=\"t[0]\">x</p>", "Thread", "never view-safe");
    assertRejected("Threads t", "<p t:text=\"t.first\">x</p>", "Thread");
    assertRejected("Threads t", "<p t:text=\"t.last\">x</p>", "Thread");
    assertRejected("Threads t", "<p t:text=\"t.getFirst()\">x</p>", "Thread");
    assertRejected("Threads t", "<p t:each=\"x : t\">x</p>", "Thread", "never view-safe");
    assertRejected("Threads t", "<p t:each=\"x, st : t\">x</p>", "Thread");
    assertRejected("Threads t", "<p t:with=\"x=t[0]\">x</p>", "Thread");
  }

  @Test
  void recordWithGadgetComponentHidesTheComponent() {
    assertRejected("Holder h", "<p t:text=\"h.type\">x</p>", "Class", "never view-safe");
    assertRejected("Holder h", "<p t:text=\"h.type()\">x</p>", "Class");
    assertRejected("Holder h", "<p t:text=\"h.getType()\">x</p>");
    assertRejected("Holder h", "<p t:text=\"h.type.name\">x</p>", "Class");
  }

  @Test
  void functionsWithGadgetSignaturesFailToBuild() {
    Compilation c =
        sources()
            .java("com.example.Evil", EVIL)
            .template("page.html", "<p t:params=\"String s\" t:text=\"s\">x</p>")
            .compile();
    c.assertError("Evil.cls", "java.lang.Class", "never view-safe");
    c.assertError("Evil.any", "java.lang.Object", "never view-safe");
    c.assertError("Evil.loader", "java.lang.ClassLoader", "never view-safe");
    c.assertError("Evil.load", "java.lang.ClassLoader", "never view-safe");
    c.assertError("Evil.runtime", "java.lang.Runtime", "never view-safe");
    assertNull(c.generatedSource("thwart.generated.PageTemplate"), "nothing may be generated");
  }

  private static final String EVIL =
      """
      package com.example;
      import dev.chainguard.thwart.runtime.*;
      @TemplateFunctions("evil")
      public final class Evil {
        private Evil() {}
        @TemplateFunction public static Class<?> cls(String n) { return null; }
        @TemplateFunction public static Object any(String n) { return n; }
        @TemplateFunction public static ClassLoader loader() { return null; }
        @TemplateFunction public static String load(ClassLoader l) { return ""; }
        @TemplateFunction public static Runtime runtime() { return Runtime.getRuntime(); }
      }
      """;

  @Test
  void viewSafeGetterOnTemplateViewStillWorks() {
    // Control: the same Gadget class is usable through its view-safe getters.
    sources()
        .template("page.html", "<p t:import=\"com.example.*\" t:params=\"Gadget g\" t:text=\"g.name\">x</p>")
        .compile()
        .assertSuccess();
  }
}
