package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.TemplateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Generated sources contain no reflection, dynamic code loading or ambient authority, for a set
 * of templates that exercises every attribute family and expression form (principle 4).
 */
class GeneratedCodeTest {

  private static final Pattern FORBIDDEN =
      Pattern.compile(
          "java\\.lang\\.reflect|java\\.lang\\.invoke|MethodHandle|VarHandle|\\bgetClass\\s*\\(|forName\\s*\\("
              + "|getDeclared\\w*\\s*\\(|getMethods?\\s*\\(|getFields?\\s*\\(|getConstructors?\\s*\\(|setAccessible"
              + "|newInstance|ClassLoader|ServiceLoader|ScriptEngine|javax\\.script|org\\.springframework"
              + "|(?i:ognl|spel)|sun\\.misc|Unsafe|\\.class\\b|java\\.lang\\.reflect\\.Proxy|\\bSystem\\.|\\bRuntime\\b"
              + "|ProcessBuilder|\\bThread\\b|java\\.io\\.File|java\\.nio\\.file|\\bnative\\b|Serializ");

  private static final String ALLOWED = "actual.getClass().getName()";

  private static final String NS = "xmlns:t=\"https://tessera.dev\"";

  @Test
  void generatedSourcesContainNoReflection() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Owner", "package com.example; public record Owner(String name, java.util.List<Pet> pets) {}")
            .java("com.example.Pet", "package com.example; public record Pet(String name, Kind kind, int age) {}")
            .java("com.example.Kind", "package com.example; public enum Kind { CAT, DOG }")
            .java(
                "com.example.Fmt",
                "package com.example; import dev.tessera.runtime.*; @TemplateFunctions(\"fmt\") public final class Fmt {"
                    + " private Fmt() {} @TemplateFunction public static String up(String s) { return s.toUpperCase(); } }")
            .messages("title=Owner {0}\n")
            .template(
                "fragments/layout.html",
                "<html " + NS + " t:fragment=\"layout(Fragment body, String title)\">"
                    + "<head><title t:text=\"title\">t</title><script t:src=\"@{/js/app.js}\"></script></head>"
                    + "<body><div t:replace=\"body\">b</div></body></html>")
            .template(
                "owners/details.html",
                "<html " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner, SafeHtml note, String q, Map<String, Owner> owners\""
                    + " t:replace=\"~{fragments/layout :: layout(~{::body}, #{title(owner.name)})}\">"
                    + "<body t:remove=\"tag\">"
                    + "<h1 t:text=\"owner.name\">n</h1><div t:utext=\"note\">n</div>"
                    + "<p>[[fmt.up(owner.name)]] [[#strings.isEmpty(q)]]</p>"
                    + "<a t:href=\"@{/owners/{id}(id=owner.name, q=q)}\" t:title=\"|Owner ${owner.name}|\">x</a>"
                    + "<ul t:unless=\"owner.pets.isEmpty()\"><li t:each=\"pet, st : owner.pets\" t:classappend=\"st.odd ? 'odd'\""
                    + " t:attr=\"data-age=pet.age, data-idx=st.index\">"
                    + "<span t:if=\"pet.age > 1 and pet.kind == 'CAT'\" t:text=\"pet.name\">p</span>"
                    + "<b t:switch=\"pet.kind\"><i t:case=\"'DOG'\">dog</i><i t:case=\"*\">other</i></b>"
                    + "<i t:with=\"n=pet.name ?: 'none'\" t:text=\"n\">n</i>"
                    + "</li></ul>"
                    + "<form method=\"post\" t:action=\"@{/owners}\" t:object=\"owner\"><input t:field=\"*{name}\">"
                    + "<span t:if=\"fields.hasErrors('name')\" t:errors=\"*{name}\">e</span></form>"
                    + "<input type=\"checkbox\" t:checked=\"owner.pets.isEmpty()\">"
                    + "<script>var owner = [[owner.name]]; var n = [[owner.pets.size()]]; var o = [[owner]]; var m = [[owners]];</script>"
                    + "<div t:remove=\"all\">gone</div>"
                    + "</body></html>")
            .compile()
            .assertSuccess();

    List<String> names =
        List.of(
            "tessera.generated.TesseraRegistry",
            "tessera.generated.fragments.LayoutTemplate",
            "tessera.generated.owners.DetailsTemplate");
    List<String> hits = new ArrayList<>();
    for (String name : names) {
      String src = c.generatedSource(name);
      assertNotNull(src, "generated " + name);
      // The one permitted use: naming the wrong model attribute type in a TemplateModelException.
      src = src.replace(ALLOWED, "");
      Matcher m = FORBIDDEN.matcher(src);
      while (m.find()) {
        int line = (int) src.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
        hits.add(name + ":" + line + ": " + m.group());
      }
    }
    assertTrue(hits.isEmpty(), () -> "forbidden constructs in generated code:\n" + String.join("\n", hits));
  }

  @Test
  void generatedRegistryIsAnExactLookup() {
    TemplateRegistry r =
        Compilation.builder()
            .template("greeting.html", "<p>hi</p>")
            .template("owners/details.html", "<p>d</p>")
            .compile()
            .assertSuccess()
            .registry();
    assertTrue(r.find("greeting").isPresent());
    assertTrue(r.find("owners/details").isPresent());
    for (String name :
        List.of(
            "greeting :: x",
            "greeting::x",
            "~{greeting}",
            "__${T(java.lang.Runtime)}__::.x",
            "Greeting",
            "greeting.html",
            "/greeting",
            "./greeting",
            "owners/../greeting",
            "owners//details",
            "owners\\details",
            "owners.details",
            "OwnersDetails",
            "tessera.generated.GreetingTemplate",
            "greeting ",
            "")) {
      assertTrue(r.find(name).isEmpty(), name);
    }
  }

  @Test
  void patternCatchesReflection() {
    // Control: the scan recognises the constructs it is there to catch.
    for (String bad :
        List.of(
            "x.getClass()",
            "Class.forName(\"x\")",
            "java.lang.reflect.Method m",
            "String.class",
            "MethodHandles.lookup()",
            "c.getDeclaredMethod(\"x\")",
            "System.getenv()",
            "Runtime.getRuntime()",
            "new javax.script.ScriptEngineManager()")) {
      assertTrue(FORBIDDEN.matcher(bad).find(), bad);
    }
  }
}
