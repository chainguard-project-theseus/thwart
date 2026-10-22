package dev.tessera.spring.context;

import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Forms;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.ClassUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.support.BindStatus;
import org.springframework.web.servlet.support.RequestContext;

/**
 * {@link Forms} over the model's {@link BindingResult}s. Values and errors come from Spring's
 * {@link BindStatus} (not HTML-escaped: Tessera escapes for the output context). Objects
 * without a {@code BindingResult} in the model use the current value and have no errors.
 */
final class SpringForms implements Forms {
  static final String CSRF_ATTRIBUTE = "_csrf";

  private static final boolean SECURITY_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.security.web.csrf.CsrfToken", SpringForms.class.getClassLoader());

  private final RequestContext requestContext;
  private final Map<String, ?> model;
  private final HttpServletRequest request;

  SpringForms(RequestContext requestContext, Map<String, ?> model, HttpServletRequest request) {
    this.requestContext = requestContext;
    this.model = model;
    this.request = request;
  }

  @Override
  public String value(String objectName, String path, Object currentValue) {
    if (!hasBindingResult(objectName)) {
      return Escapers.toText(currentValue);
    }
    return bindStatus(objectName + "." + path).getDisplayValue();
  }

  @Override
  public List<String> errors(String objectName, String path) {
    if (!hasBindingResult(objectName)) {
      return Collections.emptyList();
    }
    return Arrays.asList(bindStatus(objectName + "." + path).getErrorMessages());
  }

  /**
   * The {@code _csrf} request attribute, read lazily so that a deferred token is only loaded
   * (and a session only created) when a template actually renders a POST form.
   */
  @Override
  public Optional<CsrfToken> csrfToken() {
    return SECURITY_PRESENT ? Security.csrfToken(request) : Optional.empty();
  }

  private boolean hasBindingResult(String objectName) {
    return model.get(BindingResult.MODEL_KEY_PREFIX + objectName) instanceof BindingResult;
  }

  private BindStatus bindStatus(String path) {
    return new BindStatus(requestContext, path, false);
  }

  /** Holds the Spring Security references so they load only when Security is on the classpath. */
  private static final class Security {
    static Optional<CsrfToken> csrfToken(HttpServletRequest request) {
      Object attribute = request.getAttribute(CSRF_ATTRIBUTE);
      if (attribute instanceof org.springframework.security.web.csrf.CsrfToken) {
        org.springframework.security.web.csrf.CsrfToken token =
            (org.springframework.security.web.csrf.CsrfToken) attribute;
        if (token.getToken() != null) {
          return Optional.of(new CsrfToken(token.getParameterName(), token.getToken()));
        }
      }
      return Optional.empty();
    }
  }
}
