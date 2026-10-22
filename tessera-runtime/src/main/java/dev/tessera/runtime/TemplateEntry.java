package dev.tessera.runtime;

import java.util.List;
import java.util.Map;

/**
 * One compiled template in a {@link TemplateRegistry}.
 *
 * @param name view name, e.g. {@code owners/ownerDetails}
 * @param params declared {@code t:params}, in order
 * @param renderer generated adapter that pulls each param out of a model map by name, checks
 *     its type with {@code instanceof} (throwing {@link TemplateModelException} on mismatch;
 *     a missing attribute is passed as {@code null}) and calls the typed render method
 */
public record TemplateEntry(String name, List<Param> params, ModelRenderer renderer) {

  public TemplateEntry {
    params = List.copyOf(params);
  }

  /** A declared parameter. {@code type} is the source-level Java type, e.g. {@code java.util.List<com.x.OwnerView>}. */
  public record Param(String name, String type) {}

  /** Renders the template from a name-to-value model. */
  @FunctionalInterface
  public interface ModelRenderer {
    void render(Map<String, ?> model, RenderContext ctx, Output out);
  }
}
