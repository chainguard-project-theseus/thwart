package dev.tessera.spring.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
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

  /** Binds {@code values} onto a fresh owner and returns the model Spring MVC would build. */
  private static Map<String, Object> bind(Map<String, String> values) {
    DataBinder binder = new DataBinder(new Owner(), "owner");
    binder.bind(new MutablePropertyValues(values));
    return new HashMap<>(binder.getBindingResult().getModel());
  }

  @Test
  void localeComesFromLocaleResolver() {
    assertEquals(Locale.GERMAN, create(Map.of()).locale());
  }

  @Test
  void localeFallsBackToRequestLocale() {
    request.removeAttribute(DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE);
    request.addPreferredLocale(Locale.ENGLISH);
    RenderContext ctx = create(Map.of());
    assertEquals(Locale.ENGLISH, ctx.locale());
    assertEquals("Hello Ann", ctx.messages().get("greeting", "Ann"));
  }

  @Test
  void messagesResolveThroughMessageSourceInRequestLocale() {
    assertEquals("Hallo Ann", create(Map.of()).messages().get("greeting", "Ann"));
  }

  @Test
  void missingMessageRendersKeyAndLocale() {
    assertEquals("??nope_de??", create(Map.of()).messages().get("nope"));
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
    var links =
        SpringRenderContexts.links((path, names, values) -> "/ctx" + path, encoding);
    assertEquals(
        "/ctx/owners;jsessionid=1", links.build("/owners", new String[0], new Object[0]));
  }

  @Test
  void linksUseRequestContextPath() {
    RenderContext ctx = create(Map.of());
    String url;
    try {
      url = ctx.links().build("/owners/{id}", new String[] {"id"}, new Object[] {7});
    } catch (UnsupportedOperationException e) {
      // TODO(#6): remove once DefaultLinks.build lands.
      Assumptions.abort("DefaultLinks.build not implemented yet (#6)");
      return;
    }
    assertEquals("/app/owners/7", url);
  }

  @Test
  void formsWithoutBindingResultUseCurrentValue() {
    Owner owner = new Owner();
    Forms forms = create(Map.of("owner", owner)).forms();
    assertEquals("Smith", forms.value("owner", "lastName", "Smith"));
    assertEquals("", forms.value("owner", "lastName", null));
    assertEquals(List.of(), forms.errors("owner", "lastName"));
    assertFalse(forms.hasErrors("owner", "*"));
  }

  @Test
  void formsShowBoundValueWhenBindSucceeded() {
    Forms forms = create(bind(Map.of("lastName", "Smith", "age", "42"))).forms();
    assertEquals("Smith", forms.value("owner", "lastName", "ignored"));
    assertEquals("42", forms.value("owner", "age", 42));
    assertFalse(forms.hasErrors("owner", "age"));
  }

  @Test
  void formsShowRejectedValueAndResolvedErrorAfterFailedBind() {
    Forms forms = create(bind(Map.of("age", "abc"))).forms();
    assertEquals("abc", forms.value("owner", "age", 0));
    assertEquals(List.of("Alter muss eine Zahl sein"), forms.errors("owner", "age"));
    assertTrue(forms.hasErrors("owner", "age"));
    assertFalse(forms.hasErrors("owner", "lastName"));
  }

  @Test
  void rejectedValueIsNotHtmlEscapedByForms() {
    // Tessera escapes for the output context; double escaping would corrupt the value.
    Map<String, Object> model = bind(Map.of("lastName", "<b>&"));
    Forms forms = create(model).forms();
    assertEquals("<b>&", forms.value("owner", "lastName", null));
  }

  @Test
  void formsResolveNestedPathsAndGlobalErrors() {
    Map<String, Object> model = bind(Map.of("address.city", "Nowhere"));
    BindingResult result = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "owner");
    result.rejectValue("address.city", "invalid");
    result.rejectValue("lastName", "required", "Last name is required");
    result.reject("duplicate", "Owner already exists");
    Forms forms = create(model).forms();

    assertEquals("Nowhere", forms.value("owner", "address.city", null));
    assertEquals(List.of("ungültig"), forms.errors("owner", "address.city"));
    assertEquals(List.of("Last name is required"), forms.errors("owner", "lastName"));
    assertEquals(
        List.of("ungültig", "Last name is required", "Owner already exists"),
        forms.errors("owner", "*"));
    assertTrue(forms.hasErrors("owner", "*"));
  }

  @Test
  void formsForUnknownObjectHaveNoErrors() {
    Forms forms = create(bind(Map.of("age", "abc"))).forms();
    assertEquals(List.of(), forms.errors("other", "age"));
    assertEquals("x", forms.value("other", "age", "x"));
  }

  @Test
  void csrfTokenFromRequestAttribute() {
    request.setAttribute("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "s3cret"));
    assertEquals(
        Optional.of(new Forms.CsrfToken("_csrf", "s3cret")), create(Map.of()).forms().csrfToken());
  }

  @Test
  void noCsrfTokenWhenAttributeAbsent() {
    assertEquals(Optional.empty(), create(Map.of()).forms().csrfToken());
  }

  @Test
  void csrfAttributeOfWrongTypeIsIgnored() {
    request.setAttribute("_csrf", "forged");
    assertEquals(Optional.empty(), create(Map.of()).forms().csrfToken());
  }
}
