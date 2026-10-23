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
import dev.tessera.spring.testing.SpringTestContext;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end MVC tests over hand-written registry and template doubles. MockMvc is built from the
 * context (not {@code @AutoConfigureMockMvc}, whose package differs by Boot version), and the
 * Spring test context comes from {@link SpringTestContext}, which runs on every generation.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(classes = TesseraMvcTest.App.class)
class TesseraMvcTest {

  @Autowired WebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

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
  static final class GreetingTemplate implements Template {
    private final String name;

    GreetingTemplate(String name) {
      this.name = name;
    }

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
            Collections.singletonList(new TemplateEntry.Param("name", "java.lang.String")),
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
        new TemplateEntry(
            "owners/details",
            Collections.<TemplateEntry.Param>emptyList(),
            (model, ctx, out) -> out.write("<h1>details</h1>"));
    return TemplateRegistry.of(Arrays.asList(greeting, details));
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
