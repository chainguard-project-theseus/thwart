package dev.tessera.spring.view;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.servlet.View;

/**
 * Renders a compiled Tessera template as {@code text/html;charset=UTF-8}. The output is buffered
 * so a failure mid-render (for example a {@code TemplateModelException}) never commits a partial
 * page; it is then written to the response stream as UTF-8 bytes with a {@code Content-Length}.
 * As with Spring's {@code AbstractView} and Thymeleaf's view, the handler's {@code
 * @PathVariable} values ({@link View#PATH_VARIABLES}) are merged under the model, so a model
 * attribute of the same name wins. A template still reads only its declared {@code t:params}.
 *
 * <p>The page also gets a strict {@code Content-Security-Policy} for the nonce its scripts carry,
 * unless a policy is already set ({@code sendPolicy}); {@code tessera.csp.enabled=false} turns it off.
 */
public final class TesseraView implements View {

  public static final String CONTENT_TYPE = "text/html;charset=UTF-8";

  static final String CSP_HEADER = "Content-Security-Policy";

  /**
   * The response wrapper of Spring Security's {@code HeaderWriterFilter} (the same name from 4.2
   * through 7.x): its headers are written when the response commits, after the view.
   */
  static final String DEFERRED_HEADERS =
      "org.springframework.security.web.header.HeaderWriterFilter$HeaderWriterResponse";

  /** A CSP {@code base64-value}: anything else could change the policy it is spliced into. */
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9+/_-]+={0,2}");

  private final TemplateEntry.ModelRenderer renderer;
  private final RenderContextFactory contexts;
  private final boolean csp;

  private TesseraView(
      TemplateEntry.ModelRenderer renderer, RenderContextFactory contexts, boolean csp) {
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.csp = csp;
  }

  /** {@link #of(TemplateEntry, RenderContextFactory, boolean)} with the policy header on. */
  public static TesseraView of(TemplateEntry entry, RenderContextFactory contexts) {
    return of(entry, contexts, true);
  }

  /**
   * A view over a registry entry; the entry's adapter pulls its typed params from the model. A
   * {@link Date} in the model (such as the {@code timestamp} of Boot's error model) is not
   * view-safe; it is passed converted to a param declared {@code java.time.Instant}, or {@code
   * java.time.ZonedDateTime} in the JVM's default zone (the zone {@code Date.toString()} uses).
   * With {@code csp} false the view sends no policy header ({@code tessera.csp.enabled=false}).
   */
  static TesseraView of(TemplateEntry entry, RenderContextFactory contexts, boolean csp) {
    Set<String> instantParams = new HashSet<>();
    Set<String> zonedParams = new HashSet<>();
    for (TemplateEntry.Param p : entry.params()) {
      if ("java.time.Instant".equals(p.type())) {
        instantParams.add(p.name());
      } else if ("java.time.ZonedDateTime".equals(p.type())) {
        zonedParams.add(p.name());
      }
    }
    TemplateEntry.ModelRenderer renderer = entry.renderer();
    if (instantParams.isEmpty() && zonedParams.isEmpty()) {
      return new TesseraView(renderer, contexts, csp);
    }
    return new TesseraView(
        (model, ctx, out) ->
            renderer.render(convertDates(model, instantParams, zonedParams), ctx, out),
        contexts,
        csp);
  }

  private static Map<String, ?> convertDates(
      Map<String, ?> model, Set<String> instantParams, Set<String> zonedParams) {
    Map<String, Object> converted = null;
    for (Map.Entry<String, ?> e : model.entrySet()) {
      boolean zoned = zonedParams.contains(e.getKey());
      if ((zoned || instantParams.contains(e.getKey())) && e.getValue() instanceof Date) {
        if (converted == null) {
          converted = new HashMap<>(model);
        }
        // Not Date.toInstant(): java.sql.Date throws from it.
        Instant instant = Instant.ofEpochMilli(((Date) e.getValue()).getTime());
        converted.put(e.getKey(), zoned ? instant.atZone(ZoneId.systemDefault()) : instant);
      }
    }
    return converted == null ? model : converted;
  }

  @Override
  public String getContentType() {
    return CONTENT_TYPE;
  }

  @Override
  public void render(
      Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    Map<String, ?> m = withPathVariables(model, request);
    RenderContext ctx = contexts.create(request, response, m);
    StringOutput out = new StringOutput(4096);
    renderer.render(m, ctx, out);
    response.setContentType(CONTENT_TYPE);
    if (csp) {
      sendPolicy(ctx, response);
    }
    byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  /** The strict policy for {@code nonce}: only scripts carrying it, and what they load, run. */
  static String policy(String nonce) {
    return "script-src 'nonce-" + nonce + "' 'strict-dynamic'; object-src 'none';"
        + " base-uri 'none'";
  }

  /**
   * Sets {@link #policy} for the context's nonce, unless the context has no nonce or a policy may
   * already be decided: the response has a {@code Content-Security-Policy} header, or Spring
   * Security will write its headers on commit ({@link #DEFERRED_HEADERS}), where its policy writer
   * backs off from any header already there, so ours would replace the application's. Apps on
   * Spring Security add the policy with a {@code HeaderWriter} (guide §4.9). A nonce that is not a
   * CSP {@code base64-value} is an error rather than spliced into the header.
   */
  static void sendPolicy(RenderContext ctx, HttpServletResponse response) {
    String nonce = ctx.cspNonce().orElse(null);
    if (nonce == null || response.containsHeader(CSP_HEADER) || deferredHeaders(response)) {
      return;
    }
    if (!NONCE.matcher(nonce).matches()) {
      throw new IllegalStateException(
          "The CSP nonce is not base64, so it cannot go in a Content-Security-Policy header");
    }
    response.setHeader(CSP_HEADER, policy(nonce));
  }

  private static boolean deferredHeaders(ServletResponse response) {
    for (ServletResponse r = response; ; r = ((ServletResponseWrapper) r).getResponse()) {
      if (r.getClass().getName().equals(DEFERRED_HEADERS)) {
        return true;
      }
      if (!(r instanceof ServletResponseWrapper)) {
        return false;
      }
    }
  }

  /**
   * The model over the request's {@link View#PATH_VARIABLES}, merged in the order of Spring's
   * {@code AbstractView.createMergedOutputModel}: path variables first, then the model. The
   * caller's model is not changed.
   */
  static Map<String, ?> withPathVariables(Map<String, ?> model, HttpServletRequest request) {
    Object vars = request.getAttribute(View.PATH_VARIABLES);
    if (!(vars instanceof Map) || ((Map<?, ?>) vars).isEmpty()) {
      return model == null ? Collections.emptyMap() : model;
    }
    Map<String, Object> merged = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : ((Map<?, ?>) vars).entrySet()) {
      if (e.getKey() instanceof String) {
        merged.put((String) e.getKey(), e.getValue());
      }
    }
    if (model != null) {
      merged.putAll(model);
    }
    return merged;
  }
}
