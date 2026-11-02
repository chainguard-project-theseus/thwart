package dev.tessera.runtime.internal;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateModelException;
import dev.tessera.runtime.TemplateRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One compiled template in a {@link TemplateRegistry}.
 *
 * <p>Components: {@code name} is the view name, e.g. {@code owners/ownerDetails}; {@code params}
 * are the declared {@code t:params}, in order; {@code renderer} is the generated adapter that
 * pulls each param out of a model map by name, checks its type with {@code instanceof} (throwing
 * {@link TemplateModelException} on mismatch; a missing attribute is passed as {@code null}) and
 * calls the typed render method.
 */
public final class TemplateEntry {
  private final String name;
  private final List<Param> params;
  private final ModelRenderer renderer;

  public TemplateEntry(String name, List<Param> params, ModelRenderer renderer) {
    this.name = name;
    this.params = copyOf(params);
    this.renderer = renderer;
  }

  public String name() {
    return name;
  }

  public List<Param> params() {
    return params;
  }

  public ModelRenderer renderer() {
    return renderer;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TemplateEntry)) {
      return false;
    }
    TemplateEntry other = (TemplateEntry) o;
    return Objects.equals(name, other.name)
        && params.equals(other.params)
        && Objects.equals(renderer, other.renderer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, params, renderer);
  }

  @Override
  public String toString() {
    return "TemplateEntry[name=" + name + ", params=" + params + ", renderer=" + renderer + "]";
  }

  /** Unmodifiable copy that rejects null elements, as {@code List.copyOf} does. */
  private static List<Param> copyOf(List<Param> params) {
    List<Param> copy = new ArrayList<>(params);
    for (Param p : copy) {
      Objects.requireNonNull(p);
    }
    return Collections.unmodifiableList(copy);
  }

  /** A declared parameter. {@code type} is the source-level Java type, e.g. {@code java.util.List<com.x.OwnerView>}. */
  public static final class Param {
    private final String name;
    private final String type;

    public Param(String name, String type) {
      this.name = name;
      this.type = type;
    }

    public String name() {
      return name;
    }

    public String type() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Param)) {
        return false;
      }
      Param other = (Param) o;
      return Objects.equals(name, other.name) && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, type);
    }

    @Override
    public String toString() {
      return "Param[name=" + name + ", type=" + type + "]";
    }
  }

  /** Renders the template from a name-to-value model. */
  @FunctionalInterface
  public interface ModelRenderer {
    void render(Map<String, ?> model, RenderContext ctx, Output out);
  }
}
