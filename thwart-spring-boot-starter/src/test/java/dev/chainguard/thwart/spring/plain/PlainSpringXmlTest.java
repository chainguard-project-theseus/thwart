package dev.chainguard.thwart.spring.plain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.XmlWebApplicationContext;

/** XML MVC config ({@code thwart-mvc.xml}) declaring {@code ThwartConfiguration} as a bean. */
class PlainSpringXmlTest {

  @Test
  void xmlWiringRendersTemplatesAndRedirects() throws Exception {
    XmlWebApplicationContext context = new XmlWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.setConfigLocation("classpath:dev/chainguard/thwart/spring/plain/thwart-mvc.xml");
    context.refresh();
    try {
      MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
      String page =
          mvc.perform(get("/page"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertTrue(page.contains("<p id=\"hello\">Hello, Ada</p>"), page);
      mvc.perform(post("/signup").param("name", "Ada"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/page"));
    } finally {
      context.close();
    }
  }
}
