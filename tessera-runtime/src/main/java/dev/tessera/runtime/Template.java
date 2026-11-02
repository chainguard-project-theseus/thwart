package dev.tessera.runtime;

import dev.tessera.runtime.internal.Output;
import dev.tessera.runtime.internal.StringOutput;

/**
 * A compiled template bound to its arguments. Generated classes implement this; there is no
 * way to construct a Template from template source at runtime.
 */
public interface Template {

  /** The view name, e.g. {@code owners/ownerDetails} (template path without {@code .html}). */
  String templateName();

  /**
   * Renders this template to {@code out}. Generated code implements and calls this, and
   * {@code TesseraView} streams through it; {@link Output} is internal on purpose (#762), since no
   * app in the bench corpus writes one. Apps render with {@link #renderToString} or the view
   * resolver. The same holds where {@code Fragment.Body} names {@code Output} and
   * {@code TemplateRegistry} names {@code TemplateEntry}: the starter and generated code call
   * those members, apps don't.
   */
  void render(RenderContext ctx, Output out);

  /** Convenience: renders to a String. */
  default String renderToString(RenderContext ctx) {
    StringOutput out = new StringOutput();
    render(ctx, out);
    return out.toString();
  }
}
