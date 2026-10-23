package dev.tessera.spring.e2e;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.spring.testing.SpringTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A {@code @WebMvcTest} slice includes the Tessera auto-configuration (through the slice's
 * imports file, which is named differently on each Boot generation). Without it the view name
 * would not resolve and the response would be empty or an error. The Boot 2/3 test modules
 * rewrite the {@code WebMvcTest} import to its old package.
 */
@ExtendWith(SpringTestContext.class)
@WebMvcTest
@ContextConfiguration(classes = WebMvcSliceTest.App.class)
class WebMvcSliceTest {

  @Autowired MockMvc mvc;

  @Test
  void sliceRendersTesseraViews() throws Exception {
    mvc.perform(get("/page"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Hello, Ada")));
  }

  @Import(SmokeController.class)
  static class App {}
}
