package dev.chainguard.thwart.spring.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.runtime.Forms;
import dev.chainguard.thwart.runtime.Links;
import dev.chainguard.thwart.runtime.RenderContext;
import dev.chainguard.thwart.spring.CspNonce;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

class SpringRenderContextsTest {

  /** Form bean (a JavaBean, as Spring binding requires). */
  public static class Owner {
    private String lastName;
    private int age;
    private Address address = new Address();
    private List<String> roles;

    public String getLastName() {
      return lastName;
    }

    public void setLastName(String lastName) {
      this.lastName = lastName;
    }

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }

    public Address getAddress() {
      return address;
    }

    public void setAddress(Address address) {
      this.address = address;
    }

    public List<String> getRoles() {
      return roles;
    }

    public void setRoles(List<String> roles) {
      this.roles = roles;
    }
  }

  public static class Address {
    private String city;

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }
  }

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    StaticWebApplicationContext wac = new StaticWebApplicationContext();
    wac.getStaticMessageSource().addMessage("greeting", Locale.GERMAN, "Hallo {0}");
    wac.getStaticMessageSource().addMessage("greeting", Locale.ENGLISH, "Hello {0}");
    wac.getStaticMessageSource()
        .addMessage("typeMismatch.owner.age", Locale.GERMAN, "Alter muss eine Zahl sein");
    wac.getStaticMessageSource().addMessage("invalid", Locale.GERMAN, "ungültig");
    wac.refresh();
    request = new MockHttpServletRequest("GET", "/app/owners");
    request.setContextPath("/app");
    request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, wac);
    request.setAttribute(
        DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE, new FixedLocaleResolver(Locale.GERMAN));
    response = new MockHttpServletResponse();
  }

  private RenderContext create(Map<String, ?> model) {
    return SpringRenderContexts.create(request, response, model);
  }

  private static Map<String, String> map(String... keysAndValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  /** Binds {@code values} onto a fresh owner and returns the model Spring MVC would build. */
  private static Map<String, Object> bind(Map<String, String> values) {
    DataBinder binder = new DataBinder(new Owner(), "owner");
    binder.bind(new MutablePropertyValues(values));
    return new HashMap<>(binder.getBindingResult().getModel());
  }

  /** The context's CSP nonce is the request's (#335): created once, or the one the application set. */
  @Test
  void cspNonceIsTheRequestAttribute() {
    String nonce = create(Collections.<String, Object>emptyMap()).cspNonce().get();
    assertEquals(nonce, request.getAttribute(CspNonce.ATTRIBUTE));
    assertEquals(nonce, create(Collections.<String, Object>emptyMap()).cspNonce().get());
    assertEquals(nonce, CspNonce.get(request));
    request.setAttribute(CspNonce.ATTRIBUTE, "app-nonce");
    assertEquals("app-nonce", create(Collections.<String, Object>emptyMap()).cspNonce().get());
  }

  @Test
  void localeComesFromLocaleResolver() {
    assertEquals(Locale.GERMAN, create(Collections.<String, Object>emptyMap()).locale());
  }

  @Test
  void localeFallsBackToRequestLocale() {
    request.removeAttribute(DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE);
    request.addPreferredLocale(Locale.ENGLISH);
    RenderContext ctx = create(Collections.<String, Object>emptyMap());
    assertEquals(Locale.ENGLISH, ctx.locale());
    assertEquals("Hello Ann", ctx.messages().get("greeting", "Ann"));
  }

  @Test
  void messagesResolveThroughMessageSourceInRequestLocale() {
    assertEquals("Hallo Ann", create(Collections.<String, Object>emptyMap()).messages().get("greeting", "Ann"));
  }

  @Test
  void missingMessageRendersKeyAndLocale() {
    assertEquals("??nope_de??", create(Collections.<String, Object>emptyMap()).messages().get("nope"));
  }

  @Test
  void linksAreEncodedThroughResponse() {
    MockHttpServletResponse encoding =
        new MockHttpServletResponse() {
          @Override
          public String encodeURL(String url) {
            return url + ";jsessionid=1";
          }
        };
    Links links =
        SpringRenderContexts.create(request, encoding, Collections.<String, Object>emptyMap()).links();
    assertEquals("/app/owners;jsessionid=1", links.encode(links.contextPath() + "/owners"));
  }

  @Test
  void linksUseRequestContextPath() {
    RenderContext ctx = create(Collections.<String, Object>emptyMap());
    assertEquals("/app", ctx.links().contextPath());
    assertEquals("/app/owners/7", ctx.links().encode("/app/owners/7"));
  }

  @Test
  void formsWithoutBindingResultUseCurrentValue() {
    Owner owner = new Owner();
    Forms forms = create(Collections.singletonMap("owner", owner)).forms();
    assertEquals("Smith", forms.value("owner", "lastName", "Smith"));
    assertEquals("", forms.value("owner", "lastName", null));
    assertEquals(Collections.emptyList(), forms.errors("owner", "lastName"));
    assertFalse(forms.hasErrors("owner", "*"));
  }

  @Test
  void formsShowBoundValueWhenBindSucceeded() {
    Forms forms = create(bind(map("lastName", "Smith", "age", "42"))).forms();
    assertEquals("Smith", forms.value("owner", "lastName", "ignored"));
    assertEquals("42", forms.value("owner", "age", 42));
    assertFalse(forms.hasErrors("owner", "age"));
  }

  @Test
  void formsShowRejectedValueAndResolvedErrorAfterFailedBind() {
    Forms forms = create(bind(map("age", "abc"))).forms();
    assertEquals("abc", forms.value("owner", "age", 0));
    assertEquals(Arrays.asList("Alter muss eine Zahl sein"), forms.errors("owner", "age"));
    assertTrue(forms.hasErrors("owner", "age"));
    assertFalse(forms.hasErrors("owner", "lastName"));
  }

  @Test
  void selectedValueIsNullOnlyWhenBoundValueIsNull() {
    // Like SelectedValueComparator: a null bound value selects no option, a rejected value does.
    Forms unbound = create(Collections.singletonMap("owner", new Owner())).forms();
    assertNull(unbound.selectedValue("owner", "lastName", null));
    assertEquals("Smith", unbound.selectedValue("owner", "lastName", "Smith"));
    Forms forms = create(bind(map("age", "abc"))).forms();
    assertNull(forms.selectedValue("owner", "lastName", null));
    assertEquals("abc", forms.selectedValue("owner", "age", null));
  }

  @Test
  void selectedComparesLikeSelectedValueComparator() {
    // #126: a Collection value selects the candidates it contains (checkboxes, radios and
    // <select multiple> bound to it), with or without a BindingResult; null selects nothing.
    MutablePropertyValues values = new MutablePropertyValues();
    values.add("roles", new String[] {"ROLE_A", "ROLE_B"});
    values.add("age", "42");
    DataBinder binder = new DataBinder(new Owner(), "owner");
    binder.bind(values);
    Forms forms = create(new HashMap<>(binder.getBindingResult().getModel())).forms();
    assertTrue(forms.selected("owner", "roles", null, "ROLE_A"));
    assertTrue(forms.selected("owner", "roles", null, "ROLE_B"));
    assertFalse(forms.selected("owner", "roles", null, "ROLE_C"));
    assertFalse(forms.selected("owner", "roles", null, "ROLE"));
    assertTrue(forms.selected("owner", "age", 0, "42"));
    assertFalse(forms.selected("owner", "age", 0, "4"));
    assertFalse(forms.selected("owner", "lastName", null, ""));
    // After a failed bind the rejected value matches, and so does the raw property value (as in
    // SelectedValueComparator, which also compares BindStatus.getActualValue()).
    Forms rejected = create(bind(map("age", "abc"))).forms();
    assertTrue(rejected.selected("owner", "age", 0, "abc"));
    assertTrue(rejected.selected("owner", "age", 0, "0"));
    assertFalse(rejected.selected("owner", "age", 0, "1"));

    Forms unbound = create(Collections.singletonMap("owner", new Owner())).forms();
    assertTrue(unbound.selected("owner", "roles", Arrays.asList("ROLE_A", "ROLE_B"), "ROLE_B"));
    assertTrue(unbound.selected("owner", "roles", new String[] {"ROLE_A"}, "ROLE_A"));
    assertFalse(unbound.selected("owner", "roles", Arrays.asList("ROLE_A"), "ROLE_B"));
    assertFalse(unbound.selected("owner", "roles", null, ""));
    assertTrue(unbound.selected("owner", "age", 42, "42"));
  }

  @Test
  void rejectedValueIsNotHtmlEscapedByForms() {
    // Thwart escapes for the output context; double escaping would corrupt the value.
    Map<String, Object> model = bind(map("lastName", "<b>&"));
    Forms forms = create(model).forms();
    assertEquals("<b>&", forms.value("owner", "lastName", null));
  }

  @Test
  void formsResolveNestedPathsAndGlobalErrors() {
    Map<String, Object> model = bind(map("address.city", "Nowhere"));
    BindingResult result = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "owner");
    result.rejectValue("address.city", "invalid");
    result.rejectValue("lastName", "required", "Last name is required");
    result.reject("duplicate", "Owner already exists");
    Forms forms = create(model).forms();

    assertEquals("Nowhere", forms.value("owner", "address.city", null));
    assertEquals(Arrays.asList("ungültig"), forms.errors("owner", "address.city"));
    assertEquals(Arrays.asList("Last name is required"), forms.errors("owner", "lastName"));
    assertEquals(
        Arrays.asList("ungültig", "Last name is required", "Owner already exists"),
        forms.errors("owner", "*"));
    assertTrue(forms.hasErrors("owner", "*"));
    assertEquals(Arrays.asList("Owner already exists"), forms.errors("owner", "global"));
    assertTrue(forms.hasErrors("owner", "global"));
  }

  @Test
  void fieldErrorsAreNotGlobalErrors() {
    Map<String, Object> model = bind(map("lastName", ""));
    BindingResult result = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "owner");
    result.rejectValue("lastName", "required", "Last name is required");
    Forms forms = create(model).forms();
    assertTrue(forms.hasErrors("owner", "*"));
    assertEquals(Collections.emptyList(), forms.errors("owner", "global"));
    assertFalse(forms.hasErrors("owner", "global"));
    assertEquals(Collections.emptyList(), create(bind(map("age", "abc"))).forms().errors("other", "global"));
  }

  @Test
  void formsForUnknownObjectHaveNoErrors() {
    Forms forms = create(bind(map("age", "abc"))).forms();
    assertEquals(Collections.emptyList(), forms.errors("other", "age"));
    assertEquals("x", forms.value("other", "age", "x"));
  }

  @Test
  void csrfTokenFromRequestAttribute() {
    request.setAttribute(
        CsrfToken.class.getName(), new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "s3cret"));
    assertEquals(
        Optional.of(new Forms.CsrfToken("_csrf", "s3cret", "X-CSRF-TOKEN")),
        create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }

  @Test
  void csrfTokenSetByRealCsrfFilterWithoutUnderscoreAlias() throws Exception {
    // Runs Spring Security's own CsrfFilter (every supported version), then drops the "_csrf"
    // alias, which 6.x sets whatever the parameter name: the token must come from the CsrfToken
    // class-name key that the real filter sets. In 6.x and later the token is deferred and
    // XOR-masked once per request, so both reads (a form input and <t:csrf-meta>) see one value.
    HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository();
    repo.setParameterName("token");
    new CsrfFilter(repo).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    request.removeAttribute("_csrf");
    Forms forms = create(Collections.<String, Object>emptyMap()).forms();
    Optional<Forms.CsrfToken> token = forms.csrfToken();
    assertTrue(token.isPresent());
    assertEquals("token", token.get().parameterName());
    assertEquals("X-CSRF-TOKEN", token.get().headerName());
    assertFalse(token.get().token().isEmpty());
    assertEquals(token, forms.csrfToken());
  }

  @Test
  void csrfHeaderNameOfCookieRepository() throws Exception {
    new CsrfFilter(new CookieCsrfTokenRepository()).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    assertEquals("X-XSRF-TOKEN", create(Collections.<String, Object>emptyMap()).forms().csrfToken().get().headerName());
  }

  @Test
  void csrfHeaderNameSetOnRepository() throws Exception {
    HttpSessionCsrfTokenRepository repo = new HttpSessionCsrfTokenRepository();
    repo.setHeaderName("X-App-Csrf");
    new CsrfFilter(repo).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    assertEquals("X-App-Csrf", create(Collections.<String, Object>emptyMap()).forms().csrfToken().get().headerName());
  }

  @Test
  void noCsrfTokenWhenAttributeAbsent() {
    assertEquals(Optional.empty(), create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }

  @Test
  void csrfAttributeOfWrongTypeIsIgnored() {
    request.setAttribute(CsrfToken.class.getName(), "forged");
    assertEquals(Optional.empty(), create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }
}
