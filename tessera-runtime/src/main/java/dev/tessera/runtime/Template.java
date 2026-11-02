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

  /** Renders this template to {@code out}. */
  void render(RenderContext ctx, Output out);

  /** Convenience: renders to a String. */
  default String renderToString(RenderContext ctx) {
    StringOutput out = new StringOutput();
    render(ctx, out);
    return out.toString();
  }
}
