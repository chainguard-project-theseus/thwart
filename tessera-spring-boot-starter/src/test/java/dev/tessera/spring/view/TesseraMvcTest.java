package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.Template;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateModelException;
import dev.tessera.runtime.TemplateRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/** End-to-end MVC tests over hand-written registry and template doubles. */
@SpringBootTest(classes = TesseraMvcTest.App.class)
@AutoConfigureMockMvc
class TesseraMvcTest {

  @Autowired MockMvc mvc;

  @Test
  void viewNameRendersWithModel() throws Exception {
    mvc.perform(get("/greet").param("name", "Ada"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string("<p>Hello Ada</p>"));
  }

  @Test
  void nestedViewNameRenders() throws Exception {
    mvc.perform(get("/view").param("v", "owners/details"))
        .andExpect(status().isOk())
        .andExpect(content().string("<h1>details</h1>"));
  }

  @Test
  void templateReturnValueRenders() throws Exception {
    mvc.perform(get("/typed").param("name", "Grace"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string("<p>Hello Grace</p>"));
  }

  @Test
  void unknownNameFallsThroughToNextResolver() throws Exception {
    mvc.perform(get("/view").param("v", "nope"))
        .andExpect(status().isOk())
        .andExpect(content().string("fallback:nope"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../greeting",
        "./greeting",
        "/greeting",
        "greeting/",
        "GREETING",
        " greeting",
        "greeting :: frag",
        "greeting::frag",
        "__${T(java.lang.Runtime).getRuntime().exec('id')}__::x",
        "__${T(java.lang.Runtime)}__",
        "greeting?y",
        "greeting.html",
        "owners/../greeting",
        "owners//details",
        "owners\\details",
        "forward:greeting"
      })
  void hostileNamesNeverResolve(String name) throws Exception {
    mvc.perform(get("/view").param("v", name))
        .andExpect(status().isOk())
        .andExpect(content().string("fallback:" + name));
  }

  @Test
  void modelTypeMismatchFailsWithTemplateModelException() {
    Exception e = assertThrows(Exception.class, () -> mvc.perform(get("/mismatch")));
    Throwable cause = e;
    while (cause != null && !(cause instanceof TemplateModelException)) {
      cause = cause.getCause();
    }
    assertThat(cause)
        .isInstanceOf(TemplateModelException.class)
        .hasMessage(
            "Model attribute 'name' for template 'greeting' must be java.lang.String but was"
                + " java.lang.Integer");
  }

  @Test
  void redirectIsLeftToSpring() throws Exception {
    mvc.perform(get("/redirect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/greet"));
  }

  /** A hand-written stand-in for a generated template with {@code t:params="String name"}. */
  record GreetingTemplate(String name) implements Template {
    @Override
    public String templateName() {
      return "greeting";
    }

    @Override
    public void render(RenderContext ctx, Output out) {
      render(name, ctx, out);
    }

    static void render(String name, RenderContext ctx, Output out) {
      out.write("<p>Hello ");
      out.write(name); // test data only; real generated code escapes
      out.write("</p>");
    }
  }

  static TemplateRegistry registry() {
    TemplateEntry greeting =
        new TemplateEntry(
            "greeting",
            List.of(new TemplateEntry.Param("name", "java.lang.String")),
            (model, ctx, out) -> {
              Object v = model.get("name");
              if (v != null && !(v instanceof String)) {
                throw new TemplateModelException(
                    "Model attribute 'name' for template 'greeting' must be java.lang.String but"
                        + " was "
                        + v.getClass().getName());
              }
              GreetingTemplate.render((String) v, ctx, out);
            });
    TemplateEntry details =
        new TemplateEntry("owners/details", List.of(), (model, ctx, out) -> out.write("<h1>details</h1>"));
    return TemplateRegistry.of(List.of(greeting, details));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    TemplateRegistry testRegistry() {
      return registry();
    }

    @Bean
    RenderContextFactory testContexts() {
      return (request, response, model) -> RenderContext.defaults();
    }

    /** Stands in for "the next resolver": renders the name it was asked for as plain text. */
    @Bean
    ViewResolver fallbackResolver() {
      return new FallbackResolver();
    }

    @Bean
    Controllers controllers() {
      return new Controllers();
    }
  }

  static final class FallbackResolver implements ViewResolver, Ordered {
    @Override
    public View resolveViewName(String viewName, java.util.Locale locale) {
      if (viewName.startsWith("redirect:")) {
        return null;
      }
      return new View() {
        @Override
        public String getContentType() {
          return "text/plain;charset=UTF-8";
        }

        @Override
        public void render(
            Map<String, ?> model,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response)
            throws Exception {
          response.setContentType(getContentType());
          response.getWriter().write("fallback:" + viewName);
        }
      };
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE - 1;
    }
  }

  @Controller
  static class Controllers {
    @GetMapping("/greet")
    String greet(@RequestParam("name") String name, Model model) {
      model.addAttribute("name", name);
      return "greeting";
    }

    @GetMapping("/view")
    String view(@RequestParam("v") String v) {
      return v;
    }

    @GetMapping("/typed")
    GreetingTemplate typed(@RequestParam("name") String name) {
      return new GreetingTemplate(name);
    }

    @GetMapping("/mismatch")
    String mismatch(Model model) {
      model.addAttribute("name", 42);
      return "greeting";
    }

    @GetMapping("/redirect")
    String redirect() {
      return "redirect:/greet";
    }
  }
}
