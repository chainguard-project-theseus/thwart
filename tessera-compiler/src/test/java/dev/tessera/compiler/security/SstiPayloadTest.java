package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Every Thymeleaf SSTI payload class (from the public CVEs and write-ups, grouped below), placed in
 * every expression site a template has, must fail to build with a Tessera diagnostic on that
 * site's line, and nothing may be generated. This suite must stay green forever: if a payload
 * starts compiling, that is a vulnerability, not a test to update.
 */
class SstiPayloadTest {

  /** Payloads, grouped by the attack class they stand for. */
  static final List<String> PAYLOADS =
      List.of(
          // Static type references, including the whitespace variants that beat Thymeleaf's
          // lexical pre-check (CVE-2021-43466, CVE-2026-40478).
          "T(java.lang.Runtime).getRuntime().exec('id')",
          "T (java.lang.Runtime).getRuntime().exec('id')",
          "T\t(java.lang.Runtime).getRuntime()",
          "T\n(java.lang.Runtime).getRuntime()",
          "s + T(java.lang.System).getenv()",
          "''+T(java.lang.Runtime)",
          // Instantiation.
          "new java.lang.ProcessBuilder('id').start()",
          "new\tjava.io.File('/etc/passwd')",
          "new java.util.Scanner(T(java.lang.Runtime).getRuntime().exec('id').getInputStream()).next()",
          "new String('x')",
          // Preprocessing (Spring View Manipulation).
          "__${s}__",
          "__${T(java.lang.Runtime).getRuntime().exec('id')}__::.x",
          "s + __${s}__",
          "|__${s}__|",
          // Expression utility objects and context variables (CVE-2026-40477).
          "#ctx",
          "#vars",
          "#root",
          "#this",
          "#execInfo",
          "#execInfo.templateName",
          "#request.getParameter('x')",
          "#response",
          "#session",
          "#servletContext",
          "#httpServletRequest",
          "#ctx.getVariable('x')",
          "#vars.get('x')",
          "#ctx.getVariable('x').getClass()",
          // Ambient names a template might reach for, including the generated code's own locals.
          "ctx",
          "ctx.messages()",
          "out",
          "request.getParameter('x')",
          "session",
          "param.x",
          "application",
          "execInfo",
          "springRequestContext",
          "thymeleafRequestContext",
          // Reflection chains and Object methods.
          "s.getClass()",
          "s.class",
          "s.getClass().forName('java.lang.Runtime')",
          "s.class.forName('java.lang.Runtime').getMethod('getRuntime').invoke(null)",
          "pet.getClass().getClassLoader()",
          "pet.name.getClass()",
          "xs.getClass().getProtectionDomain()",
          "s?.getClass()",
          "s.hashCode()",
          "s.toString()",
          "s.wait()",
          "s.notify()",
          "pet.equals(s)",
          "color.declaringClass",
          "color.getDeclaringClass().getClassLoader()",
          "s.intern()",
          "xs.stream()",
          "xs.iterator()",
          "xs.toArray()",
          "s['class']",
          "pet['class']",
          // Spring bean references and Spring-class gadgets (CVE-2023-38286, CVE-2026-40478).
          "@environment.getProperty('x')",
          "@systemProperties['user.home']",
          "T(org.springframework.util.StreamUtils).copyToString(null, null)",
          "T(org.springframework.cglib.core.ReflectUtils).defineClass('x', null, null)",
          "new org.springframework.core.io.FileSystemResource('/etc/passwd').getInputStream()",
          "T(org.springframework.util.ReflectionUtils).findMethod(null, 'x')",
          // SpEL-only syntax.
          "xs.?[true]",
          "xs.![length]",
          "xs.^[true]",
          "xs.$[true]",
          "s = 'x'",
          "x -> x",
          "{1, 2}",
          "{'a': 1}",
          "s matches '.*'",
          "s instanceof T(String)",
          "#{T(java.lang.Runtime)}",
          "#{${s}}",
          "@{${s}}",
          "@{/a(x=${T(java.lang.Runtime)})}",
          "exec('id')",
          "getRuntime()",
          // OGNL.
          "@java.lang.Runtime@getRuntime()",
          "#_memberAccess");

  /** Every expression site, as a function from expression to markup on one line. */
  static final List<Function<String, String>> SITES =
      List.of(
          e -> "<p t:text=\"" + attr(e) + "\">x</p>",
          e -> "<p>[[" + e + "]]</p>",
          e -> "<a t:href=\"" + attr(e) + "\">x</a>",
          e -> "<i t:title=\"" + attr(e) + "\">x</i>",
          e -> "<i t:title=\"" + attr(e) + "\">x</i>",
          e -> "<i t:if=\"" + attr(e) + "\">x</i>",
          e -> "<i t:unless=\"" + attr(e) + "\">x</i>",
          e -> "<i t:each=\"v : " + attr(e) + "\">x</i>",
          e -> "<i t:with=\"v=" + attr(e) + "\">x</i>",
          e -> "<i t:classappend=\"" + attr(e) + "\">x</i>",
          e -> "<i t:switch=\"" + attr(e) + "\"><b t:case=\"'a'\">a</b></i>",
          e -> "<script>var v = [[" + e + "]];</script>",
          e -> "<i t:insert=\"" + attr(e) + "\">x</i>",
          e -> "<i t:insert=\"~{page :: f(" + attr(e) + ")}\">x</i>",
          e -> "<form t:object=\"pet\"><input t:field=\"" + attr(e) + "\"></form>",
          e -> "<form t:object=\"pet\"><b t:errors=\"" + attr(e) + "\">x</b></form>");

  private static String attr(String expr) {
    return expr.replace("&", "&amp;").replace("\"", "&quot;");
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void payloadFailsToBuildInEverySite(String payload) {
    StringBuilder t = new StringBuilder();
    t.append("<div t:import=\"com.example.*\" t:params=\"String s, Pet pet, List<String> xs, Color color\">\n");
    List<Integer> lines = new ArrayList<>();
    for (Function<String, String> site : SITES) {
      lines.add((int) t.chars().filter(ch -> ch == '\n').count() + 1);
      t.append(site.apply(payload)).append('\n');
    }
    t.append("<i t:fragment=\"f(String a)\">[[a]]</i>\n</div>");
    Compilation c =
        Compilation.builder()
            .java("com.example.Pet", "package com.example; public record Pet(String name) {}")
            .java("com.example.Color", "package com.example; public enum Color { RED }")
            .template("page.html", t.toString())
            .compile();
    List<String> errors = c.errors();
    List<Integer> missing = new ArrayList<>();
    for (int line : lines) {
      // A payload spanning lines may be reported on any of them; the next site starts later.
      int next = lines.indexOf(line) + 1 < lines.size() ? lines.get(lines.indexOf(line) + 1) : line + 1;
      boolean found = false;
      for (int l = line; l < next; l++) {
        String prefix = "page.html:" + l + ":";
        found |= errors.stream().anyMatch(e -> e.contains(prefix));
      }
      if (!found) {
        missing.add(line);
      }
    }
    assertTrue(
        missing.isEmpty(),
        () -> "payload " + payload + " was not rejected on lines " + missing + "; errors:\n" + String.join("\n", errors));
    assertNull(c.generatedSource("tessera.generated.PageTemplate"), "nothing may be generated");
  }
}
