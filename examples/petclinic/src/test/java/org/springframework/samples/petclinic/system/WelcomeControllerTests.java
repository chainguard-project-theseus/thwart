/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(WelcomeController.class)
@DisabledInNativeImage
@DisabledInAotMode
class WelcomeControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void welcome() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(view().name("welcome"))
			.andExpect(content().contentType("text/html;charset=UTF-8"))
			.andExpect(content().string(stringContainsInOrder("<!DOCTYPE html>", "<html>",
					"<title>PetClinic :: a Spring Framework demonstration</title>",
					"<a class=\"nav-link active\" href=\"/\" title=\"home page\">", "<span>Home</span>",
					"<a class=\"nav-link\" href=\"/owners/find\" title=\"find owners\">", "<span>Find Owners</span>",
					"<a class=\"nav-link\" href=\"/vets.html\" title=\"veterinarians\">",
					"<a class=\"nav-link\" href=\"/oups\"", "<h2>Welcome</h2>",
					"<img class=\"img-responsive\" src=\"/resources/images/pets.png\" />")))
			// Versioned by the starter's ResourceUrlEncodingFilter (webjars-bootstrap.version), with
			// the request's CSP nonce.
			.andExpect(content().string(matchesRegex(
					"(?s).*<script src=\"/webjars/bootstrap/\\d[^/\"]*/dist/js/bootstrap\\.bundle\\.min\\.js\" nonce=\"[A-Za-z0-9+/]{22}==\"></script>.*")));
	}

}
