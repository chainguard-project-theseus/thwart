package dev.tessera.spring.view;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.servlet.View;

/**
 * Renders a compiled Tessera template as {@code text/html;charset=UTF-8}. The output is buffered
 * so a failure mid-render (for example a {@code TemplateModelException}) never commits a partial
 * page. As with Spring's {@code AbstractView} and Thymeleaf's view, the handler's {@code
 * @PathVariable} values ({@link View#PATH_VARIABLES}) are merged under the model, so a model
 * attribute of the same name wins. A template still reads only its declared {@code t:params}.
 */
public final class TesseraView implements View {

  public static final String CONTENT_TYPE = "text/html;charset=UTF-8";

  private final TemplateEntry.ModelRenderer renderer;
  private final RenderContextFactory contexts;

  private TesseraView(TemplateEntry.ModelRenderer renderer, RenderContextFactory contexts) {
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
  }

  /**
   * A view over a registry entry; the entry's adapter pulls its typed params from the model. A
   * {@link Date} in the model (such as the {@code timestamp} of Boot's error model) is not
   * view-safe; it is passed converted to a param declared {@code java.time.Instant}, or {@code
   * java.time.ZonedDateTime} in the JVM's default zone (the zone {@code Date.toString()} uses).
   */
  public static TesseraView of(TemplateEntry entry, RenderContextFactory contexts) {
    final Map<String, Boolean> dates = new HashMap<String, Boolean>(); // param name -> zoned
    for (TemplateEntry.Param p : entry.params()) {
      if ("java.time.Instant".equals(p.type())) {
        dates.put(p.name(), Boolean.FALSE);
      } else if ("java.time.ZonedDateTime".equals(p.type())) {
        dates.put(p.name(), Boolean.TRUE);
      }
    }
    final TemplateEntry.ModelRenderer renderer = entry.renderer();
    if (dates.isEmpty()) {
      return new TesseraView(renderer, contexts);
    }
    return new TesseraView(
        (model, ctx, out) -> renderer.render(convertDates(model, dates), ctx, out), contexts);
  }

  private static Map<String, ?> convertDates(Map<String, ?> model, Map<String, Boolean> dates) {
    Map<String, Object> converted = null;
    for (Map.Entry<String, Boolean> d : dates.entrySet()) {
      Object v = model.get(d.getKey());
      if (v instanceof Date) {
        if (converted == null) {
          converted = new HashMap<String, Object>(model);
        }
        // Not Date.toInstant(): java.sql.Date throws from it.
        Instant instant = Instant.ofEpochMilli(((Date) v).getTime());
        converted.put(d.getKey(), d.getValue() ? instant.atZone(ZoneId.systemDefault()) : instant);
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
    response.getWriter().write(out.toString());
  }

  /**
   * The model over the request's {@link View#PATH_VARIABLES}, merged in the order of Spring's
   * {@code AbstractView.createMergedOutputModel}: path variables first, then the model. The
   * caller's model is not changed.
   */
  static Map<String, ?> withPathVariables(Map<String, ?> model, HttpServletRequest request) {
    Object vars = request.getAttribute(View.PATH_VARIABLES);
    if (!(vars instanceof Map) || ((Map<?, ?>) vars).isEmpty()) {
      return model == null ? Collections.<String, Object>emptyMap() : model;
    }
    Map<String, Object> merged = new LinkedHashMap<String, Object>();
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
