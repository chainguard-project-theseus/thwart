package dev.tessera.spring.context;

import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Links;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import dev.tessera.spring.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.web.servlet.support.RequestContext;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Builds the per-request {@link RenderContext}: locale from the LocaleResolver, messages from
 * the MessageSource, links with the request context path (and {@code response.encodeURL}),
 * forms from the model's {@code BindingResult}s, the CSRF token request attribute, and the
 * request's CSP nonce ({@link CspNonce}).
 */
public final class SpringRenderContexts {
  private SpringRenderContexts() {}

  /**
   * Creates the context for one render. Requires a {@code WebApplicationContext} reachable from
   * the request (always the case inside a {@code DispatcherServlet} request).
   */
  public static RenderContext create(
      HttpServletRequest request, HttpServletResponse response, Map<String, ?> model) {
    Locale locale = RequestContextUtils.getLocale(request);
    RequestContext requestContext =
        new RequestContext(request, response, request.getServletContext(), new HashMap<>(model));
    return new RenderContext(
        locale,
        messages(requestContext.getMessageSource(), locale),
        links(new DefaultLinks(request.getContextPath()), response),
        new SpringForms(requestContext, model, request),
        CspNonce.get(request));
  }

  /** {@link Messages} over a {@link MessageSource}; a missing key renders {@code ??key_locale??}. */
  static Messages messages(MessageSource messageSource, Locale locale) {
    return (key, args) -> {
      try {
        return messageSource.getMessage(key, args, locale);
      } catch (NoSuchMessageException e) {
        return "??" + key + "_" + locale + "??";
      }
    };
  }

  /** Passes every link built by {@code delegate} through {@code response.encodeURL}. */
  static Links links(Links delegate, HttpServletResponse response) {
    return (path, names, values) -> response.encodeURL(delegate.build(path, names, values));
  }
}
