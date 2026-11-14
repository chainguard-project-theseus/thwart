package dev.chainguard.thwart.compiler.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A type the generated code can't name (a non-public class, or a class nested in one, also as a
 * type argument) is not view-safe: the error is at the template position, not a javac error in
 * generated code (#269).
 */
class AccessibleTypesTest {
  private static final String PRIV = "package com.example; record PrivRec(String name) {}";
  private static final String HIDDEN = "package com.example; class Hidden { public String getId() { return \"h\"; } }";

  private static Compilation compile(String holder, String params, String body) {
    return Compilation.builder()
        .java("com.example.PrivRec", PRIV)
        .java("com.example.Hidden", HIDDEN)
        .java("com.example.Gen", "package com.example; public record Gen<K>(K k, String label) {}")
        .java("com.example.Holder", holder)
        .template("page.html", "<div t:params=\"" + params + "\">\n" + body + "</div>")
        .compile();
  }

  @Test
  void packagePrivateRecordInAListIsRejectedAtTheTemplate() {
    compile(
            "package com.example; public record Holder(java.util.List<PrivRec> v) {}",
            "com.example.Holder h",
            "<p t:each=\"x : h.v\">[[x.name]]</p>")
        .assertError("page.html:2:", "com.example.PrivRec is not public", "make it public");
  }

  @Test
  void packagePrivateRecordPropertyIsRejectedAtTheTemplate() {
    compile("package com.example; public record Holder(PrivRec v) {}", "com.example.Holder h", "<p>[[h.v.name]]</p>")
        .assertError("page.html:2:", "com.example.PrivRec is not public", "make it public");
  }

  @Test
  void nonPublicTypeArgumentOfARecordIsRejected() {
    compile(
            "package com.example; public record Holder(String s) {}",
            "java.util.List<com.example.Gen<com.example.Hidden>> l",
            "<p t:each=\"g : l\">[[g.label]]</p>")
        .assertError("page.html:1:", "com.example.Hidden is not public", "make it public");
    compile(
            "package com.example; public record Holder(java.util.List<Gen<Hidden>> l) {}",
            "com.example.Holder h",
            "<p t:each=\"g : h.l\">[[g.label]]</p>")
        .assertError("page.html:2:", "com.example.Hidden is not public", "make it public");
  }

  @Test
  void nestedTypesMustBePublicAllTheWayOut() {
    compile(
            "package com.example; public class Holder { record In(String name) {} }",
            "com.example.Holder.In i",
            "<p>[[i.name]]</p>")
        .assertError("page.html:1:", "com.example.Holder.In is not public", "make it public");
    compile(
            "package com.example; class Holder { public record In(String name) {} }",
            "com.example.Holder.In i",
            "<p>[[i.name]]</p>")
        .assertError("page.html:1:", "com.example.Holder.In is not public", "make it public");
  }

  @Test
  void scriptJsonOfARecordWithANonPublicComponentIsRejected() {
    compile(
            "package com.example; public record Holder(String s) {}",
            "com.example.Gen<java.util.List<String>> g",
            "<script>var g = [[${g}]];</script>")
        .assertSuccess();
    Compilation.builder()
        .java("com.example.PrivRec", PRIV)
        .java("com.example.Holder", "package com.example; public record Holder(String s, PrivRec p) {}")
        .template("page.html", "<div t:params=\"com.example.Holder h\">\n<script>var h = [[${h}]];</script></div>")
        .compile()
        .assertError("page.html:2:", "inside <script>", "com.example.PrivRec is not public", "make it public");
  }

  @Test
  void functionReturningANonPublicTypeIsRejectedAtTheCall() {
    String fns =
        """
        package com.example;
        import dev.chainguard.thwart.runtime.*;
        @TemplateFunctions("privs")
        public final class Privs {
          private Privs() {}
          @TemplateFunction public static java.util.List<PrivRec> all() { return java.util.List.of(new PrivRec("a")); }
        }
        """;
    Compilation.builder()
        .java("com.example.PrivRec", PRIV)
        .java("com.example.Privs", fns)
        .template("page.html", "<div>\n<p t:each=\"x : privs.all()\">[[x.name]]</p></div>")
        .compile()
        .assertError("page.html:2:", "com.example.PrivRec is not public", "make it public");
  }

  @Test
  void publicNestedRecordsWork() {
    Compilation c =
        compile(
                "package com.example; public class Holder { public record In(String name, java.util.List<Gen<In>> more) {} }",
                "com.example.Holder.In i",
                "<p>[[i.name]]</p><p t:each=\"g : i.more\">[[g.k.name]]</p>")
            .assertSuccess();
    Object leaf = c.newInstance("com.example.Holder$In", "b", java.util.List.of());
    Object gen = c.newInstance("com.example.Gen", leaf, "l");
    Object in = c.newInstance("com.example.Holder$In", "a", java.util.List.of(gen));
    assertEquals("<div>\n<p>a</p><p>b</p></div>", c.render("page", Map.of("i", in)));
  }
}
