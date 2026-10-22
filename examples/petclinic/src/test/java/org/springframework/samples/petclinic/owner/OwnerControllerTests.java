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

package org.springframework.samples.petclinic.owner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test class for {@link OwnerController}
 *
 * @author Colin But
 * @author Wick Dynex
 */
@WebMvcTest(OwnerController.class)
@DisabledInNativeImage
@DisabledInAotMode
class OwnerControllerTests {

	private static final int TEST_OWNER_ID = 1;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OwnerRepository owners;

	private Owner george() {
		Owner george = new Owner();
		george.setId(TEST_OWNER_ID);
		george.setFirstName("George");
		george.setLastName("Franklin");
		george.setAddress("110 W. Liberty St.");
		george.setCity("Madison");
		george.setTelephone("6085551023");
		Pet max = new Pet();
		PetType dog = new PetType();
		dog.setName("dog");
		max.setType(dog);
		max.setName("Max");
		max.setBirthDate(LocalDate.now());
		george.addPet(max);
		max.setId(1);
		return george;
	}

	/**
	 * An otherwise empty owner with an id. Upstream lists a bare {@code new Owner()}; Tessera's
	 * {@code @{/owners/{ownerId}(ownerId=owner.id)}} rejects a null path variable instead of
	 * rendering {@code /owners/}, and saved owners always have an id (see PORTING.md).
	 */
	private static Owner savedOwner(int id) {
		Owner owner = new Owner();
		owner.setId(id);
		return owner;
	}

	@BeforeEach
	void setup() {

		Owner george = george();
		given(this.owners.findByLastNameStartingWith(eq("Franklin"), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(george)));

		given(this.owners.findById(TEST_OWNER_ID)).willReturn(Optional.of(george));
		Visit visit = new Visit();
		visit.setDate(LocalDate.now());
		george.getPet("Max").getVisits().add(visit);

	}

	@Test
	void initCreationForm() throws Exception {
		mockMvc.perform(get("/owners/new"))
			.andExpect(status().isOk())
			.andExpect(model().attributeExists("owner"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("<title>PetClinic :: a Spring Framework demonstration</title>", "<h2>Owner</h2>", "<form class=\"form-horizontal\" id=\"add-owner-form\" method=\"post\">", "<label for=\"firstName\" class=\"col-sm-2 control-label\">First Name</label>", "<input class=\"form-control\" type=\"text\" id=\"firstName\" name=\"firstName\" value=\"\" />", "id=\"telephone\" name=\"telephone\"", "<button class=\"btn btn-primary\" type=\"submit\">Add Owner</button>")))
			.andExpect(content().string(not(containsString("has-error"))))
			.andExpect(content().string(not(containsString("t:"))));
	}

	@Test
	void processCreationFormSuccess() throws Exception {
		mockMvc
			.perform(post("/owners/new").param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "123 Caramel Street")
				.param("city", "London")
				.param("telephone", "1316761638"))
			.andExpect(status().is3xxRedirection());
	}

	@Test
	void processCreationFormHasErrors() throws Exception {
		mockMvc
			.perform(post("/owners/new").param("firstName", "Joe").param("lastName", "Bloggs").param("city", "London"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("owner"))
			.andExpect(model().attributeHasFieldErrors("owner", "address"))
			.andExpect(model().attributeHasFieldErrors("owner", "telephone"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("<div class=\"form-group\">", "id=\"firstName\" name=\"firstName\" value=\"Joe\"", "<div class=\"form-group has-error\">", "id=\"address\" name=\"address\"", "<span class=\"help-inline\">must not be blank</span>", "id=\"city\" name=\"city\" value=\"London\"")));
	}

	@Test
	void processCreationFormRejectsCityLongerThanColumn() throws Exception {
		mockMvc
			.perform(post("/owners/new").param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "123 Caramel Street")
				.param("city", "C".repeat(81))
				.param("telephone", "1316761638"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("owner"))
			.andExpect(model().attributeHasFieldErrors("owner", "city"))
			.andExpect(model().attributeHasFieldErrorCode("owner", "city", "Size"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("<div class=\"form-group has-error\">", "id=\"city\" name=\"city\" value=\"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC\"", "<span class=\"help-inline\">size must be between 0 and 80</span>")));
	}

	@Test
	void processCreationFormRejectsAddressLongerThanColumn() throws Exception {
		mockMvc
			.perform(post("/owners/new").param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "A".repeat(256))
				.param("city", "London")
				.param("telephone", "1316761638"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("owner"))
			.andExpect(model().attributeHasFieldErrors("owner", "address"))
			.andExpect(model().attributeHasFieldErrorCode("owner", "address", "Size"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("<div class=\"form-group has-error\">", "id=\"address\" name=\"address\"", "<span class=\"help-inline\">size must be between 0 and 255</span>")));
	}

	@Test
	void initFindForm() throws Exception {
		mockMvc.perform(get("/owners/find"))
			.andExpect(status().isOk())
			.andExpect(model().attributeExists("owner"))
			.andExpect(view().name("owners/findOwners"))
			.andExpect(content().string(stringContainsInOrder("<h2>Find Owners</h2>", "id=\"search-owner-form\" action=\"/owners\"", "<input class=\"form-control\" size=\"30\" maxlength=\"80\" id=\"lastName\" name=\"lastName\" value=\"\" />", "<a class=\"btn btn-primary\" href=\"/owners/new\">Add Owner</a>")))
			.andExpect(content().string(not(containsString("<p>"))));
	}

	@Test
	void processFindFormSuccess() throws Exception {
		Page<Owner> tasks = new PageImpl<>(List.of(george(), savedOwner(2)));
		when(this.owners.findByLastNameStartingWith(anyString(), any(Pageable.class))).thenReturn(tasks);
		mockMvc.perform(get("/owners?page=1"))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/ownersList"))
			.andExpect(content().string(stringContainsInOrder("<h2>Owners</h2>", "<a href=\"/owners/1\">George Franklin</a>", "<td>110 W. Liberty St.</td>", "<td>Madison</td>", "<td>6085551023</td>", "<td><span>Max</span></td>")))
			.andExpect(content().string(not(containsString("Pages:"))));
	}

	@Test
	void processFindFormRedirectsOutOfBoundsPageToFirstPage() throws Exception {
		for (int page : List.of(0, 2)) {
			mockMvc.perform(get("/owners").param("page", Integer.toString(page)).param("lastName", "Franklin"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/owners?page=1&lastName=Franklin"));
		}
	}

	@Test
	void processFindFormByLastName() throws Exception {
		Page<Owner> tasks = new PageImpl<>(List.of(george()));
		when(this.owners.findByLastNameStartingWith(eq("Franklin"), any(Pageable.class))).thenReturn(tasks);
		mockMvc.perform(get("/owners?page=1").param("lastName", "Franklin"))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/" + TEST_OWNER_ID));
	}

	@Test
	void processFindFormIgnoresSurroundingWhitespace() throws Exception {
		Page<Owner> tasks = new PageImpl<>(List.of(george()));
		when(this.owners.findByLastNameStartingWith(eq("Franklin"), any(Pageable.class))).thenReturn(tasks);

		for (String lastName : List.of(" Franklin", "Franklin ", " Franklin ")) {
			mockMvc.perform(get("/owners?page=1").param("lastName", lastName))
				.andExpect(status().is3xxRedirection())
				.andExpect(view().name("redirect:/owners/" + TEST_OWNER_ID));
		}

		verify(this.owners, times(3)).findByLastNameStartingWith(eq("Franklin"), any(Pageable.class));
	}

	@Test
	void processFindFormWithWhitespaceOnlyLastNameReturnsAllOwners() throws Exception {
		Page<Owner> tasks = new PageImpl<>(List.of(george(), savedOwner(2)));
		when(this.owners.findByLastNameStartingWith(eq(""), any(Pageable.class))).thenReturn(tasks);

		mockMvc.perform(get("/owners?page=1").param("lastName", "   "))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/ownersList"))
			.andExpect(content().string(stringContainsInOrder("<h2>Owners</h2>", "<a href=\"/owners/1\">George Franklin</a>")));

		verify(this.owners).findByLastNameStartingWith(eq(""), any(Pageable.class));
	}

	@Test
	void processFindFormNoOwnersFound() throws Exception {
		Page<Owner> tasks = new PageImpl<>(List.of());
		when(this.owners.findByLastNameStartingWith(eq("Unknown Surname"), any(Pageable.class))).thenReturn(tasks);
		mockMvc.perform(get("/owners?page=1").param("lastName", "Unknown Surname"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("owner", "lastName"))
			.andExpect(model().attributeHasFieldErrorCode("owner", "lastName", "notFound"))
			.andExpect(view().name("owners/findOwners"))
			.andExpect(content().string(stringContainsInOrder("id=\"lastName\" name=\"lastName\" value=\"Unknown Surname\"", "<p>has not been found</p>")));

	}

	@Test
	void initUpdateOwnerForm() throws Exception {
		mockMvc.perform(get("/owners/{ownerId}/edit", TEST_OWNER_ID))
			.andExpect(status().isOk())
			.andExpect(model().attributeExists("owner"))
			.andExpect(model().attribute("owner", hasProperty("lastName", is("Franklin"))))
			.andExpect(model().attribute("owner", hasProperty("firstName", is("George"))))
			.andExpect(model().attribute("owner", hasProperty("address", is("110 W. Liberty St."))))
			.andExpect(model().attribute("owner", hasProperty("city", is("Madison"))))
			.andExpect(model().attribute("owner", hasProperty("telephone", is("6085551023"))))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("id=\"firstName\" name=\"firstName\" value=\"George\"", "id=\"lastName\" name=\"lastName\" value=\"Franklin\"", "id=\"address\" name=\"address\" value=\"110 W. Liberty St.\"", "id=\"city\" name=\"city\" value=\"Madison\"", "id=\"telephone\" name=\"telephone\" value=\"6085551023\"", "<button class=\"btn btn-primary\" type=\"submit\">Update Owner</button>")));
	}

	@Test
	void processUpdateOwnerFormSuccess() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/edit", TEST_OWNER_ID).param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "123 Caramel Street")
				.param("city", "London")
				.param("telephone", "1616291589"))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/{ownerId}"));
	}

	@Test
	void processUpdateOwnerFormUnchangedSuccess() throws Exception {
		mockMvc.perform(post("/owners/{ownerId}/edit", TEST_OWNER_ID))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/{ownerId}"));
	}

	@Test
	void processUpdateOwnerFormHasErrors() throws Exception {
		mockMvc
			.perform(post("/owners/{ownerId}/edit", TEST_OWNER_ID).param("firstName", "Joe")
				.param("lastName", "Bloggs")
				.param("address", "")
				.param("telephone", ""))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasErrors("owner"))
			.andExpect(model().attributeHasFieldErrors("owner", "address"))
			.andExpect(model().attributeHasFieldErrors("owner", "telephone"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andExpect(content().string(stringContainsInOrder("id=\"firstName\" name=\"firstName\" value=\"Joe\"", "<div class=\"form-group has-error\">", "id=\"address\" name=\"address\" value=\"\"", "<span class=\"help-inline\">must not be blank</span>", "<button class=\"btn btn-primary\" type=\"submit\">Update Owner</button>")));
	}

	@Test
	void showOwner() throws Exception {
		mockMvc.perform(get("/owners/{ownerId}", TEST_OWNER_ID))
			.andExpect(status().isOk())
			.andExpect(model().attribute("owner", hasProperty("lastName", is("Franklin"))))
			.andExpect(model().attribute("owner", hasProperty("firstName", is("George"))))
			.andExpect(model().attribute("owner", hasProperty("address", is("110 W. Liberty St."))))
			.andExpect(model().attribute("owner", hasProperty("city", is("Madison"))))
			.andExpect(model().attribute("owner", hasProperty("telephone", is("6085551023"))))
			.andExpect(model().attribute("owner", hasProperty("pets", not(empty()))))
			.andExpect(model().attribute("owner",
					hasProperty("pets", hasItem(hasProperty("visits", hasSize(greaterThan(0)))))))
			.andExpect(view().name("owners/ownerDetails"))
			.andExpect(content().string(stringContainsInOrder("<h2>Owner Information</h2>", "<td><b>George Franklin</b></td>", "<td>110 W. Liberty St.</td>", "<td>Madison</td>", "<td>6085551023</td>", "<a class=\"btn btn-primary\" href=\"1/edit\">Edit Owner</a>", "<a class=\"btn btn-primary\" href=\"1/pets/new\">Add New Pet</a>", "<dd>Max</dd>", "<dd>dog</dd>", "<a href=\"1/pets/1/edit\">Edit Pet</a>", "<a href=\"1/pets/1/visits/new\">Add Visit</a>")))
			.andExpect(content().string(not(containsString("id=\"success-message\""))))
			.andExpect(content().string(not(containsString("id=\"error-message\""))));
	}

	@Test
	void ownerDataIsEscapedInEveryPage() throws Exception {
		Owner evil = george();
		evil.setFirstName("<script>alert(1)</script>");
		evil.setAddress("\"><img src=x onerror=alert(1)>");
		evil.getPets().get(0).setName("</b><script>x</script>");
		given(this.owners.findById(TEST_OWNER_ID)).willReturn(Optional.of(evil));
		given(this.owners.findByLastNameStartingWith(eq(""), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(evil, savedOwner(2))));

		for (String url : List.of("/owners/1", "/owners/1/edit", "/owners?lastName=")) {
			mockMvc.perform(get(url))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
				.andExpect(content().string(not(containsString("<script>alert(1)"))))
				.andExpect(content().string(not(containsString("<img src=x"))))
				.andExpect(content().string(not(containsString("<script>x"))));
		}
	}

	@Test
	void processUpdateOwnerFormWithIdMismatch() throws Exception {
		int pathOwnerId = 1;

		Owner owner = new Owner();
		owner.setId(2);
		owner.setFirstName("John");
		owner.setLastName("Doe");
		owner.setAddress("Center Street");
		owner.setCity("New York");
		owner.setTelephone("0123456789");

		when(owners.findById(pathOwnerId)).thenReturn(Optional.of(owner));

		mockMvc.perform(MockMvcRequestBuilders.post("/owners/{ownerId}/edit", pathOwnerId).flashAttr("owner", owner))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/" + pathOwnerId + "/edit"))
			.andExpect(flash().attributeExists("error"));
	}

}
