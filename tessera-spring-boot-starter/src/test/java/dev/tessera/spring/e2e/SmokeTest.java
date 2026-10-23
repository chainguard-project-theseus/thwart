package dev.tessera.spring.e2e;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.spring.testing.SpringTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A compiled template through the auto-configuration, with the application's {@code
 * MessageSource}, links, form binding, CSRF token and authentication: the end-to-end check that
 * runs on every Boot generation.
 */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(classes = SmokeTest.App.class)
class SmokeTest {

  @Autowired WebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void viewNameRendersCompiledTemplate() throws Exception {
    mvc.perform(get("/app/page").contextPath("/app"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string(containsString("<p id=\"hello\">Hello, Ada</p>")))
        .andExpect(content().string(containsString("href=\"/app/owners/7?q=Ada\"")))
        .andExpect(content().string(containsString("action=\"/app/signup\"")))
        .andExpect(content().string(containsString("value=\"Ada\"")))
        .andExpect(content().string(not(containsString("class=\"error\""))))
        .andExpect(content().string(not(containsString("_csrf"))));
  }

  @Test
  void templateReturnValueRenders() throws Exception {
    mvc.perform(get("/typed"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Hello, Grace")));
  }

  @Test
  void formErrorsRenderFromBindingResult() throws Exception {
    mvc.perform(post("/signup").param("name", ""))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("<span class=\"error\">Name is required</span>")));
  }

  @Test
  void validPostRedirects() throws Exception {
    mvc.perform(post("/signup").param("name", "Ada"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/page"));
  }

  @Test
  void csrfTokenRendersInPostForm() throws Exception {
    mvc.perform(
            get("/page").requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t0k")))
        .andExpect(
            content()
                .string(containsString("<input type=\"hidden\" name=\"_csrf\" value=\"t0k\"")));
  }

  @Test
  void authenticationIsVisibleToTemplates() throws Exception {
    mvc.perform(get("/page"))
        .andExpect(content().string(containsString("<p id=\"user\"></p>")))
        .andExpect(content().string(not(containsString("id=\"admin\""))));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    mvc.perform(get("/page"))
        .andExpect(content().string(containsString("<p id=\"user\">alice</p>")))
        .andExpect(content().string(containsString("<p id=\"admin\">admin</p>")));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SmokeController.class)
  static class App {}
}
