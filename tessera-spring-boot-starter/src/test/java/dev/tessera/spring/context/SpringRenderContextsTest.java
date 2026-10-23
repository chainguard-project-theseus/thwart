package dev.tessera.spring.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Links;
import dev.tessera.runtime.RenderContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;
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
        SpringRenderContexts.links((path, names, values) -> "/ctx" + path, encoding);
    assertEquals(
        "/ctx/owners;jsessionid=1", links.build("/owners", new String[0], new Object[0]));
  }

  @Test
  void linksUseRequestContextPath() {
    RenderContext ctx = create(Collections.<String, Object>emptyMap());
    assertEquals("/app/owners/7", ctx.links().build("/owners/{id}", new String[] {"id"}, new Object[] {7}));
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
  void rejectedValueIsNotHtmlEscapedByForms() {
    // Tessera escapes for the output context; double escaping would corrupt the value.
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
        SpringForms.CSRF_ATTRIBUTE, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "s3cret"));
    assertEquals(
        Optional.of(new Forms.CsrfToken("_csrf", "s3cret")), create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }

  @Test
  void noCsrfTokenWhenAttributeAbsent() {
    assertEquals(Optional.empty(), create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }

  @Test
  void csrfAttributeOfWrongTypeIsIgnored() {
    request.setAttribute(SpringForms.CSRF_ATTRIBUTE, "forged");
    assertEquals(Optional.empty(), create(Collections.<String, Object>emptyMap()).forms().csrfToken());
  }
}
