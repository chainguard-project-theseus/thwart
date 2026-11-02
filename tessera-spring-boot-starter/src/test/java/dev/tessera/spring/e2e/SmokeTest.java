package dev.tessera.spring.e2e;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.runtime.TemplateModelException;
import dev.tessera.spring.CspNonce;
import dev.tessera.spring.plain.CspPages;
import dev.tessera.spring.testing.SpringTestContext;
import java.util.Collections;
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
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
  void pathVariablesFillDeclaredParams() throws Exception {
    mvc.perform(get("/owners/7/pets/secret-pet"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<p id=\"owner\">7</p>")))
        .andExpect(content().string(containsString("<p id=\"name\"></p>")))
        // petId is a path variable but not a t:param: it never reaches the template.
        .andExpect(content().string(not(containsString("secret-pet"))));
  }

  @Test
  void modelAttributeWinsOverPathVariable() throws Exception {
    mvc.perform(get("/owners/7/as/ann"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<p id=\"owner\">7</p>")))
        .andExpect(content().string(containsString("<p id=\"name\">model-ann</p>")));
  }

  @Test
  void pathVariableOfTheWrongTypeFailsTheRender() {
    Exception e = assertThrows(Exception.class, () -> mvc.perform(get("/owners/named/7")));
    Throwable cause = e;
    while (cause != null && !(cause instanceof TemplateModelException)) {
      cause = cause.getCause();
    }
    assertNotNull(cause, "TemplateModelException in " + e);
    assertTrue(cause.getMessage().contains("'ownerId' must be java.lang.Long"), cause.getMessage());
  }

  @Test
  void fragmentViewNameRendersTheFragmentFromPathVariablesAndModel() throws Exception {
    // "smoke/rows :: row" (#131): only the <tr>, as an htmx partial; secret is not a declared param.
    mvc.perform(get("/owners/7/row"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/html;charset=UTF-8"))
        .andExpect(content().string("<tr id=\"row\"><td>7</td><td>Ann</td></tr>"));
  }

  @Test
  void formErrorsRenderFromBindingResult() throws Exception {
    mvc.perform(post("/signup").param("name", ""))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("<span class=\"error\">Name is required</span>")));
  }

  @Test
  void indexedFieldsBindThroughBindStatus() throws Exception {
    // Spring's names and ids, and @DateTimeFormat applied through the BindingResult (#123).
    mvc.perform(get("/cart"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "<input type=\"text\" id=\"lines0.name\" name=\"lines[0].name\" value=\"Tea\" />")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "<input type=\"text\" id=\"lines0.due\" name=\"lines[0].due\" value=\"05.03.2024\" />")))
        .andExpect(content().string(containsString("id=\"lines1.due\" name=\"lines[1].due\" value=\"\"")))
        // fields.value: the same display value, formatted through the BindingResult (#133).
        .andExpect(content().string(containsString("<b class=\"due\">05.03.2024</b>")))
        .andExpect(content().string(containsString("<b class=\"due\"></b>")))
        .andExpect(content().string(not(containsString("due-error"))))
        .andExpect(content().string(not(containsString("class=\"error\""))))
        .andExpect(content().string(not(containsString("has-error"))))
        // A map key path: Spring's qty[tea] name and Thymeleaf's bracket-free id (#190).
        .andExpect(
            content().string(containsString("<input type=\"text\" id=\"qtytea\" name=\"qty[tea]\" value=\"1\" />")));
  }

  @Test
  void indexedFieldErrorsRender() throws Exception {
    mvc.perform(
            post("/cart")
                .param("lines[0].name", "Tea")
                .param("lines[0].due", "31.12.2024")
                .param("lines[1].name", "")
                .param("lines[1].due", "soon")
                .param("qty[tea]", "lots"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"31.12.2024\"")))
        .andExpect(content().string(containsString("name=\"lines[1].due\" value=\"soon\"")))
        .andExpect(content().string(containsString("<div class=\"has-error\">")))
        .andExpect(content().string(containsString("<span class=\"error\">Name is required</span>")))
        .andExpect(content().string(containsString("<span class=\"error\">Failed to convert")))
        // fields.value is the rejected value, fields.errors(...).first the first error (#133).
        .andExpect(content().string(containsString("<b class=\"due\">soon</b>")))
        .andExpect(content().string(containsString("<i class=\"due-error\">Failed to convert")))
        // A map key path: the rejected value and its conversion error, through BindStatus (#190).
        .andExpect(content().string(containsString("id=\"qtytea\" name=\"qty[tea]\" value=\"lots\"")));
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

  /**
   * The CSRF token from Spring Security goes only into forms that post to the page's origin
   * (#285): a link gets it; a static action on another origin does not. A GET form's computed
   * action is a same-origin link too (#585), whatever the request passes.
   */
  @Test
  void csrfTokenOnlyInSameOriginPostForms() throws Exception {
    String token = "<input type=\"hidden\" name=\"_csrf\" value=\"t0k\" />";
    mvc.perform(
            get("/app/action")
                .contextPath("/app")
                .param("target", "/app/signup")
                .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t0k")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(containsString("<form id=\"computed\" method=\"get\" action=\"/app/search?q=")))
        .andExpect(
            content().string(containsString("<form id=\"link\" method=\"post\" action=\"/app/signup\">" + token)))
        .andExpect(
            content()
                .string(
                    containsString("<form id=\"external\" method=\"post\" action=\"https://other.example/\"></form>")));
    mvc.perform(
            get("/action")
                .param("target", "https://evil.example/steal")
                .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t0k")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString("<form id=\"computed\" method=\"get\" action=\"/search?q=")))
        .andExpect(content().string(containsString("action=\"/signup\">" + token)));
  }

  @Test
  void authenticationIsVisibleToTemplates() throws Exception {
    mvc.perform(get("/page"))
        .andExpect(content().string(containsString("<p id=\"user\"></p>")))
        .andExpect(content().string(not(containsString("id=\"admin\""))))
        .andExpect(content().string(not(containsString("perm\""))));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    mvc.perform(get("/page"))
        .andExpect(content().string(containsString("<p id=\"user\">alice</p>")))
        .andExpect(content().string(containsString("<p id=\"admin\">admin</p>")))
        .andExpect(content().string(not(containsString("perm\""))));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "bob", "pw", AuthorityUtils.createAuthorityList("PERM_EDIT")));
    mvc.perform(get("/page"))
        .andExpect(content().string(not(containsString("id=\"admin\""))))
        .andExpect(content().string(containsString("<p id=\"perm\">perm</p>")))
        .andExpect(content().string(containsString("<p id=\"anyperm\">anyperm</p>")));
  }

  /**
   * The guide's Spring Security example (#335): a {@code HeaderWriter} that puts the request's
   * nonce in the policy. Spring Security 4.2 writes headers before the view and 5.x on commit,
   * after it; both see the nonce the page was rendered with.
   */
  @Test
  void springSecurityHeaderWriterSeesThePageNonce() throws Exception {
    HeaderWriter csp =
        (request, response) ->
            response.setHeader(
                "Content-Security-Policy",
                "script-src 'nonce-" + CspNonce.get(request) + "' 'strict-dynamic'; object-src 'none'; base-uri 'self'");
    MockMvc secured =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new HeaderWriterFilter(Collections.<HeaderWriter>singletonList(csp)))
            .build();
    MvcResult result = secured.perform(get("/csp")).andExpect(status().isOk()).andReturn();
    String nonce = CspPages.assertStamped(result);
    assertEquals(
        "script-src 'nonce-" + nonce + "' 'strict-dynamic'; object-src 'none'; base-uri 'self'",
        result.getResponse().getHeader("Content-Security-Policy"));
    // The starter itself sends no policy.
    assertNull(mvc.perform(get("/csp")).andReturn().getResponse().getHeader("Content-Security-Policy"));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(SmokeController.class)
  static class App {}
}
