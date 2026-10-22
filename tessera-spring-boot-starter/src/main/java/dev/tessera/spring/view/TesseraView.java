package dev.tessera.spring.view;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.Template;
import dev.tessera.runtime.TemplateEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.servlet.View;

/**
 * Renders a compiled Tessera template as {@code text/html;charset=UTF-8}. The output is buffered
 * so a failure mid-render (for example a {@code TemplateModelException}) never commits a partial
 * page.
 */
public final class TesseraView implements View {

  public static final String CONTENT_TYPE = "text/html;charset=UTF-8";

  private final TemplateEntry.ModelRenderer renderer;
  private final RenderContextFactory contexts;

  private TesseraView(TemplateEntry.ModelRenderer renderer, RenderContextFactory contexts) {
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
  }

  /** A view over a registry entry; the entry's adapter pulls its typed params from the model. */
  public static TesseraView of(TemplateEntry entry, RenderContextFactory contexts) {
    return new TesseraView(entry.renderer(), contexts);
  }

  /** A view over a template already bound to its arguments; the model is not consulted. */
  public static TesseraView of(Template template, RenderContextFactory contexts) {
    Objects.requireNonNull(template, "template");
    return new TesseraView((model, ctx, out) -> template.render(ctx, out), contexts);
  }

  @Override
  public String getContentType() {
    return CONTENT_TYPE;
  }

  @Override
  public void render(
      Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    Map<String, ?> m = model == null ? Collections.<String, Object>emptyMap() : model;
    RenderContext ctx = contexts.create(request, response, m);
    StringOutput out = new StringOutput(4096);
    renderer.render(m, ctx, out);
    response.setContentType(CONTENT_TYPE);
    response.getWriter().write(out.toString());
  }
}
