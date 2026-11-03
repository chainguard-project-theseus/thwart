package com.example;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/** Renders the page through the real view resolver, with the message from messages.properties. */
@WebMvcTest(HelloController.class)
class HelloControllerTest {
  @Autowired MockMvc mvc;

  @Test
  void rendersEscapedName() throws Exception {
    mvc.perform(get("/").param("name", "Ann <b>"))
        .andExpect(status().isOk())
        .andExpect(content().string("<p><span>Hello</span> <b>Ann &lt;b&gt;</b></p>\n"));
  }
}
