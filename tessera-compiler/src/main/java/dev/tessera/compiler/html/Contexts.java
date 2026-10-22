package dev.tessera.compiler.html;

import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The escaping-context table (see docs/mvp-design.md). Pure functions over element and
 * attribute names; case-insensitive.
 */
public final class Contexts {
  private Contexts() {}

  private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]*");

  private static final Set<String> URL_ATTRIBUTES =
      Set.of(
          "href", "src", "action", "formaction", "cite", "poster", "background", "longdesc",
          "usemap", "ping", "xlink:href");

  /** {@code <script type>} values whose content is JavaScript or JSON. */
  private static final Set<String> SCRIPT_TYPES =
      Set.of(
          "",
          "text/javascript",
          "application/javascript",
          "text/ecmascript",
          "application/ecmascript",
          "module",
          "application/json",
          "application/ld+json");

  /**
   * Context for the value of attribute {@code attributeName} (without {@code t:}) on
   * {@code element}: {@code on*} is EVENT_HANDLER, {@code style} is STYLE, {@code srcdoc} and
   * names that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*} are FORBIDDEN, resource URLs are
   * RESOURCE_URL, other URL attributes are URL, everything else ATTRIBUTE. The values of SVG
   * {@code <animate>}/{@code <set>} ({@code values to from by}) are URL too: they can set an
   * {@code href} to {@code javascript:}.
   */
  public static OutputContext attribute(Element element, String attributeName) {
    if (attributeName == null || !PLAIN_NAME.matcher(attributeName).matches()) {
      return OutputContext.FORBIDDEN;
    }
    String attr = attributeName.toLowerCase(Locale.ROOT);
    if (attr.startsWith("on")) {
      return OutputContext.EVENT_HANDLER;
    }
    if (attr.equals("style")) {
      return OutputContext.STYLE;
    }
    if (attr.equals("srcdoc")) {
      return OutputContext.FORBIDDEN;
    }
    if (isResourceUrl(element == null ? "" : element.name().toLowerCase(Locale.ROOT), attr)) {
      return OutputContext.RESOURCE_URL;
    }
    if (URL_ATTRIBUTES.contains(attr) || isAnimationValue(element, attr)) {
      return OutputContext.URL;
    }
    return OutputContext.ATTRIBUTE;
  }

  private static boolean isAnimationValue(Element element, String attr) {
    if (element == null) {
      return false;
    }
    String name = element.name().toLowerCase(Locale.ROOT);
    return (name.equals("animate") || name.equals("set"))
        && (attr.equals("values") || attr.equals("to") || attr.equals("from") || attr.equals("by"));
  }

  private static boolean isResourceUrl(String element, String attr) {
    return switch (element) {
      // SVG <script> loads code from href / xlink:href.
      case "script" -> attr.equals("src") || attr.equals("href") || attr.equals("xlink:href");
      case "iframe", "frame", "embed" -> attr.equals("src");
      case "object" -> attr.equals("data");
      case "base", "link" -> attr.equals("href");
      default -> false;
    };
  }

  /**
   * Context for text directly inside {@code element} ({@code null} for top level): SCRIPT
   * inside {@code <script>} with no type or a JavaScript/module/JSON type, FORBIDDEN inside
   * other script types, STYLE inside {@code <style>}, otherwise TEXT.
   *
   * <p>A {@code <script>} whose type is set by a {@code t:type} attribute is FORBIDDEN: the
   * content type must be known at build time.
   */
  public static OutputContext text(Element element) {
    if (element == null) {
      return OutputContext.TEXT;
    }
    return switch (element.name().toLowerCase(Locale.ROOT)) {
      case "script" -> scriptContext(element);
      case "style" -> OutputContext.STYLE;
      default -> OutputContext.TEXT;
    };
  }

  private static OutputContext scriptContext(Element script) {
    String type = null;
    for (Attribute a : script.attributes()) {
      if (a.isTessera() && a.localName().equalsIgnoreCase("type")) {
        return OutputContext.FORBIDDEN;
      }
      if (!a.isTessera() && a.name().equalsIgnoreCase("type")) {
        type = a.value() == null ? "" : a.value();
      }
    }
    if (type == null) {
      return OutputContext.SCRIPT;
    }
    return SCRIPT_TYPES.contains(type.strip().toLowerCase(Locale.ROOT))
        ? OutputContext.SCRIPT
        : OutputContext.FORBIDDEN;
  }
}
