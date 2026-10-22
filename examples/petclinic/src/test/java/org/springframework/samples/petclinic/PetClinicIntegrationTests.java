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

package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.vet.VetRepository;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "logging.level.sql=DEBUG")
public class PetClinicIntegrationTests {

	@LocalServerPort
	int port;

	@Autowired
	private VetRepository vets;

	@Autowired
	private RestTemplateBuilder builder;

	@Test
	void findAll() {
		vets.findAll();
		vets.findAll(); // served from cache
	}

	@Test
	void ownerDetails() {
		RestTemplate template = builder.baseUri("http://localhost:" + port).build();
		ResponseEntity<String> result = template.exchange(RequestEntity.get("/owners/1").build(), String.class);
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Rendered by the compiled Tessera template, from the sample data.
		assertThat(result.getBody()).containsSubsequence("<h2>Owner Information</h2>", "<b>George Franklin</b>",
				"<td>110 W. Liberty St.</td>", "<td>Madison</td>", "<td>6085551023</td>", "href=\"1/edit\"",
				"<dd>Leo</dd>", "<dd>2010-09-07</dd>", "<dd>cat</dd>", "href=\"1/pets/1/edit\"",
				"href=\"1/pets/1/visits/new\"");
	}

	@Test
	void ownerList() {
		RestTemplate template = builder.baseUri("http://localhost:" + port).build();
		ResponseEntity<String> result = template.exchange(RequestEntity.get("/owners?lastName=").build(), String.class);
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).containsSubsequence("<h2>Owners</h2>", "<a href=\"/owners/1\">George Franklin</a>",
				"<td>Madison</td>", "Leo</span>", "<a href=\"/owners/3\">Eduardo Rodriquez</a>",
				"<span>Jewel, Rosy</span>", "<span>pages</span>", "<span>1</span>", "<a href=\"/owners?page=2\">2</a>",
				"<a class=\"fa fa-step-forward\" href=\"/owners?page=2\" title=\"Next\"></a>");
	}

	public static void main(String[] args) {
		SpringApplication.run(PetClinicApplication.class, args);
	}

}
