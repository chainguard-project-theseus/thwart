package dev.tessera.spring.e2e;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tessera.spring.plain.CspPages;
import dev.tessera.spring.testing.SpringTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** {@code tessera.csp.enabled=false}: the page keeps its nonce and no policy is sent (#757). */
@ExtendWith(SpringTestContext.class)
@SpringBootTest(classes = SmokeTest.App.class, properties = "tessera.csp.enabled=false")
class CspDisabledTest {

  @Autowired WebApplicationContext context;

  @Test
  void disabledSendsNoPolicy() throws Exception {
    MvcResult result =
        MockMvcBuilders.webAppContextSetup(context)
            .build()
            .perform(get("/csp"))
            .andExpect(status().isOk())
            .andReturn();
    CspPages.assertStamped(result);
    assertNull(result.getResponse().getHeader("Content-Security-Policy"));
  }
}
