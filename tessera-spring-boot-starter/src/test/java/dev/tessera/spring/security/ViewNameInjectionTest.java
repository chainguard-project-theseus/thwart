package dev.tessera.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.internal.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import dev.tessera.spring.testing.SpringTestContext;
import dev.tessera.spring.view.FallbackResolver;
import dev.tessera.spring.view.RenderContextFactory;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;

/**
 * Spring View Manipulation through the starter: whatever a controller returns as a view name
 * (from a parameter, header, cookie, path variable, a {@code "prefix" + input} or
 * {@code "page :: " + selector} concatenation, or a void handler's URI) is looked up exactly once,
 * in the build-time registry, verbatim except for one dropped leading {@code /} and single spaces
 * around a {@code ::} (#131). Nothing is preprocessed or evaluated, no template renders, and the
 * name falls through to the next resolver. A name can select a registered fragment entry
 * ({@code greeting :: row}) only by its exact name, as a page name selects a page: no selector,
 * arguments or traversal reach any other fragment.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(classes = ViewNameInjectionTest.App.class)
class ViewNameInjectionTest {

  /**
   * View names that Thymeleaf evaluated as expressions (Spring View Manipulation, CVE-2021-43466,
   * CVE-2026-40478), plus fragment selectors. {@code TesseraViewResolverTest} also runs them
   * against the resolver itself, over a registry of the same names.
   */
  static final List<String> PAYLOADS =
      Arrays.asList(
          "__${T(java.lang.Runtime).getRuntime().exec('id')}__::.x",
          "__${new java.util.Scanner(T(java.lang.Runtime).getRuntime().exec('id').getInputStream()).next()}__::.x",
          "__${T (java.lang.Runtime).getRuntime().exec('id')}__::.x",
          "__${new\tjava.io.File('/etc/passwd')}__::x",
          "__|${T(java.lang.Runtime)}|__::x",
          "__${T(java.lang.Runtime)}__",
          "__${#ctx}__",
          "__${@environment.getProperty('x')}__",
          "${T(java.lang.Runtime).getRuntime().exec('id')}",
          "*{T(java.lang.Runtime)}",
          "#{T(java.lang.Runtime)}",
          "@{T(java.lang.Runtime)}",
          "~{greeting :: frag}",
          "~{greeting}",
          "~{::frag}",
          "greeting :: frag",
          "greeting::frag",
          "greeting :: #main",
          "greeting :: div.x",
          "greeting :: ${T(java.lang.Runtime)}",
          "greeting :: __${T(java.lang.Runtime)}__",
          "greeting::__${7*7}__",
          // Fragment syntax Tessera doesn't support, and names near the registered "greeting :: row".
          "greeting :: row(x=1)",
          "greeting::row()",
          "greeting :: row (x)",
          "greeting :: #row",
          "greeting :: .row",
          "greeting :: tr",
          "greeting :: [id='row']",
          "greeting :: ${row}",
          "greeting :: __${row}__",
          "~{greeting :: row}",
          "greeting :: ~{row}",
          "greeting :: row :: row",
          "greeting :: greeting :: row",
          "greeting :: ROW",
          "greeting :: ../row",
          "greeting :: row/",
          "greeting :: ",
          ":: row",
          "this :: row",
          "../greeting :: row",
          "//greeting :: row",
          "owners/../greeting :: row",
          "greeting.html :: row",
          "greeting : : row",
          "owners/details :: row",
          "|greeting|",
          "greeting ",
          " greeting",
          "greeting/",
          "GREETING",
          "greeting\n",
          "Greeting",
          "greeting.html",
          "./greeting",
          "//greeting",
          "/./greeting",
          "/../greeting",
          "/greeting :: frag",
          "../greeting",
          "owners/../greeting",
          "owners/./details",
          "owners//details",
          "owners\\details",
          "owners%2Fdetails",
          "greeting;x",
          "greeting?x",
          "greeting#x",
          "greeting\u0000",
          "forward:greeting",
          "redirect:greeting",
          // More near misses of the registered page and fragment names.
          "nope",
          "x?y",
          "../x",
          "x :: frag",
          "__${T(java.lang.Runtime)}__::.x",
          "~{greeting :: row",
          "greeting ::",
          "greeting :: r ow",
          "greeting :: row\u0000x",
          "greeting :: row.html",
          "greeting :: owners/details",
          "Greeting :: row",
          "./greeting :: row",
          "greeting/ :: row",
          "owners :: details :: row",
          "redirect:greeting :: row",
          "//owners/details",
          "/ greeting",
          "/greeting/",
          "\\greeting");

  @Autowired WebApplicationContext context;
  @Autowired RecordingRegistry registry;
  @Autowired FallbackResolver fallback;
  @Autowired Controllers controllers;
  MockMvc mvc;

  @BeforeEach
  void reset() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    registry.lookups.clear();
    registry.renders.set(0);
    fallback.names.clear();
    controllers.lastPath = null;
  }

  private void assertNeverRendered(String expectedName) {
    assertThat(registry.renders.get()).as("templates rendered").isZero();
    // Exactly one lookup, of exactly the name the controller produced.
    if (!expectedName.startsWith("redirect:") && !expectedName.startsWith("forward:")) {
      assertThat(registry.lookups).containsExactly(lookupName(expectedName));
      // Boot's ContentNegotiatingViewResolver asks every resolver, so the next one sees it too.
      assertThat(fallback.names).containsExactly(expectedName);
    }
  }

  /**
   * The name the resolver looks up (written out here rather than shared, as an oracle): less one
   * leading {@code /}; with a {@code ::}, the trimmed halves joined by {@code " :: "}.
   */
  static String lookupName(String name) {
    int sep = name.indexOf("::");
    if (sep < 0) {
      return name.startsWith("/") ? name.substring(1) : name;
    }
    String page = name.substring(0, sep).trim();
    return (page.startsWith("/") ? page.substring(1) : page) + " :: " + name.substring(sep + 2).trim();
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void requestParameterViewName(String payload) throws Exception {
    ResultActions result = mvc.perform(get("/param").param("v", payload));
    if (!payload.startsWith("redirect:")) {
      result.andExpect(status().isOk()).andExpect(content().string("fallback:" + payload));
    }
    assertNeverRendered(payload);
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void headerViewName(String payload) throws Exception {
    if (payload.indexOf('\n') >= 0 || payload.indexOf('\u0000') >= 0) {
      return; // not a valid header value
    }
    mvc.perform(get("/header").header("X-View", payload));
    assertNeverRendered(payload);
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void cookieViewName(String payload) throws Exception {
    String encoded = URLEncoder.encode(payload, "UTF-8").replace("+", "%20");
    mvc.perform(get("/cookie").cookie(new Cookie("view", encoded)));
    assertNeverRendered(payload);
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void concatenatedViewName(String payload) throws Exception {
    mvc.perform(get("/lang").param("lang", payload));
    assertNeverRendered("user/" + payload + "/welcome");
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void fragmentSelectorFromInput(String payload) throws Exception {
    mvc.perform(get("/section").param("section", payload));
    assertNeverRendered("greeting :: " + payload);
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
  void pathVariableViewName(String payload) throws Exception {
    if (payload.indexOf('/') >= 0 || payload.indexOf('\\') >= 0 || payload.indexOf('\u0000') >= 0
        || payload.indexOf('%') >= 0 || payload.indexOf(';') >= 0 || payload.trim().isEmpty()
        || !payload.equals(payload.trim()) || payload.startsWith(".")) {
      return; // the servlet container would not route these to the handler
    }
    boolean spring4Html = SpringVersion.getVersion().startsWith("4.") && payload.endsWith(".html");
    try {
      mvc.perform(get("/path/{v}", payload));
    } catch (ServletException e) {
      // Spring 4.3 favours the path extension: for "greeting.html" the
      // ContentNegotiatingViewResolver picks Boot's InternalResourceViewResolver, whose forward
      // would loop back to this handler.
      assertThat(spring4Html).as("unexpected " + e).isTrue();
      assertThat(e).hasMessageContaining("Circular view path");
    }
    assertThat(controllers.lastPath).as("path variable").isEqualTo(payload);
    if (spring4Html) {
      // It also asks every resolver for the name plus the ".html" of the requested media type:
      // a second exact lookup, still never evaluated or rendered.
      assertThat(registry.renders.get()).as("templates rendered").isZero();
      assertThat(registry.lookups).containsExactly(payload, payload + ".html");
      assertThat(fallback.names).containsExactly(payload, payload + ".html");
      return;
    }
    assertNeverRendered(payload);
  }

  @Test
  void voidHandlerUriBecomesViewNameButNeverResolves() throws Exception {
    // Spring derives the view name from the URI for a void handler: the classic variant.
    String payload = "__${T(java.lang.Runtime).getRuntime().exec('id')}__::.x";
    mvc.perform(get("/doc/{p}", payload)).andExpect(status().isOk());
    assertThat(registry.renders.get()).isZero();
    assertThat(registry.lookups).hasSize(1);
    assertThat(registry.lookups.get(0)).startsWith("doc/__$");
    assertThat(fallback.names).hasSize(1);
    assertThat(registry.lookups).containsExactly(lookupName(fallback.names.get(0)));
  }

  @Test
  void exactNameStillRenders() throws Exception {
    // Control: the same plumbing renders a real template name.
    mvc.perform(get("/param").param("v", "greeting"))
        .andExpect(status().isOk())
        .andExpect(content().string("<p>greeting</p>"));
    assertThat(registry.renders.get()).isEqualTo(1);
    assertThat(registry.lookups).containsExactly("greeting");
  }

  @Test
  void leadingSlashSelectsOnlyTheSameTemplate() throws Exception {
    // One leading "/" is dropped (#131): "/greeting" is "greeting", nothing more.
    mvc.perform(get("/param").param("v", "/greeting"))
        .andExpect(status().isOk())
        .andExpect(content().string("<p>greeting</p>"));
    assertThat(registry.renders.get()).isEqualTo(1);
    assertThat(registry.lookups).containsExactly("greeting");
  }

  @Test
  void fragmentNameSelectsOnlyThatFragment() throws Exception {
    // A registered fragment entry (#131) renders by its name, with the spaces Thymeleaf allows.
    for (String name : Arrays.asList("greeting :: row", "greeting::row", "/greeting  ::  row")) {
      registry.lookups.clear();
      registry.renders.set(0);
      mvc.perform(get("/param").param("v", name))
          .andExpect(status().isOk())
          .andExpect(content().string("<tr><td>row</td></tr>"));
      assertThat(registry.renders.get()).isEqualTo(1);
      assertThat(registry.lookups).containsExactly("greeting :: row");
    }
  }

  @Test
  void selectorFromInputSelectsOnlyARegisteredFragment() throws Exception {
    // "greeting :: " + input reaches the page's registered fragments, as "prefix" + input reaches
    // registered pages, and nothing else (the payloads above).
    mvc.perform(get("/section").param("section", "row"))
        .andExpect(status().isOk())
        .andExpect(content().string("<tr><td>row</td></tr>"));
    assertThat(registry.lookups).containsExactly("greeting :: row");
  }

  /** A registry that records every lookup and every render. */
  static final class RecordingRegistry implements TemplateRegistry {
    final List<String> lookups = new CopyOnWriteArrayList<>();
    final AtomicInteger renders = new AtomicInteger();
    private final TemplateRegistry delegate;

    RecordingRegistry() {
      TemplateEntry.ModelRenderer r =
          (model, ctx, out) -> {
            renders.incrementAndGet();
            out.write("<p>greeting</p>");
          };
      this.delegate =
          TemplateRegistry.of(
              Arrays.asList(
                  new TemplateEntry("greeting", Collections.<TemplateEntry.Param>emptyList(), r),
                  new TemplateEntry(
                      "owners/details", Collections.<TemplateEntry.Param>emptyList(), r),
                  new TemplateEntry(
                      "user/en/welcome", Collections.<TemplateEntry.Param>emptyList(), r)),
              Collections.singletonList(
                  new TemplateEntry(
                      "greeting :: row",
                      Collections.<TemplateEntry.Param>emptyList(),
                      (model, ctx, out) -> {
                        renders.incrementAndGet();
                        out.write("<tr><td>row</td></tr>");
                      })));
    }

    @Override
    public Optional<TemplateEntry> find(String viewName) {
      lookups.add(viewName);
      return delegate.find(viewName);
    }

    @Override
    public Collection<TemplateEntry> all() {
      return delegate.all();
    }

    @Override
    public Collection<TemplateEntry> fragments() {
      return delegate.fragments();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    RecordingRegistry testRegistry() {
      return new RecordingRegistry();
    }

    @Bean
    RenderContextFactory testContexts() {
      return (request, response, model) -> RenderContext.defaults();
    }

    @Bean
    FallbackResolver fallbackResolver() {
      return new FallbackResolver();
    }

    @Bean
    Controllers controllers() {
      return new Controllers();
    }
  }

  @Controller
  static class Controllers {
    volatile String lastPath;

    @GetMapping("/param")
    String param(@RequestParam("v") String v) {
      return v;
    }

    @GetMapping("/header")
    String header(@RequestHeader("X-View") String v) {
      return v;
    }

    @GetMapping("/cookie")
    String cookie(@CookieValue("view") String v) {
      return v; // @CookieValue has already URL-decoded it
    }

    @GetMapping("/lang")
    String lang(@RequestParam("lang") String lang) {
      return "user/" + lang + "/welcome";
    }

    @GetMapping("/section")
    String section(@RequestParam("section") String section) {
      return "greeting :: " + section;
    }

    // ".+" turns off Spring 4.3's suffix pattern matching (on in Boot 1.5), which would cut the
    // path variable at a dot, so every generation passes the whole payload to the controller.
    @GetMapping("/path/{v:.+}")
    String path(@PathVariable("v") String v) {
      lastPath = v;
      return v;
    }

    @GetMapping("/doc/{p}")
    void doc(@PathVariable("p") String p) {}
  }
}
