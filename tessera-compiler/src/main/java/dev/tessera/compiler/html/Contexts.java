package dev.tessera.compiler.html;

import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;

/**
 * The escaping-context table (see docs/mvp-design.md). Pure functions over element and
 * attribute names; case-insensitive.
 */
public final class Contexts {
  private Contexts() {}

  /**
   * Context for the value of attribute {@code attributeName} (without {@code t:}) on
   * {@code element}: {@code on*} is EVENT_HANDLER, {@code style} is STYLE, {@code srcdoc} and
   * names that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*} are FORBIDDEN, resource URLs are
   * RESOURCE_URL, other URL attributes are URL, everything else ATTRIBUTE.
   */
  public static OutputContext attribute(Element element, String attributeName) {
    throw new UnsupportedOperationException("TODO(#2): Contexts.attribute");
  }

  /**
   * Context for text directly inside {@code element} ({@code null} for top level): SCRIPT
   * inside {@code <script>} with no type or a JavaScript/module/JSON type, FORBIDDEN inside
   * other script types, STYLE inside {@code <style>}, otherwise TEXT.
   */
  public static OutputContext text(Element element) {
    throw new UnsupportedOperationException("TODO(#2): Contexts.text");
  }
}
