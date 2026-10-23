package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
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
      Backports.setOf(
          "href", "src", "action", "formaction", "cite", "poster", "background", "longdesc",
          "usemap", "ping", "xlink:href");

  /** {@code <script type>} values whose content is JavaScript or JSON. */
  private static final Set<String> SCRIPT_TYPES =
      Backports.setOf(
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
   * {@code element}: {@code on*} and {@link #isFrameworkDirective framework directives} are
   * EVENT_HANDLER, {@code style} is STYLE, {@code srcdoc} and
   * names that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*} are FORBIDDEN, resource URLs are
   * RESOURCE_URL, other URL attributes are URL, everything else ATTRIBUTE. The values of SVG
   * {@code <animate>}/{@code <set>} ({@code values to from by}) are URL too: they can set an
   * {@code href} to {@code javascript:}. The {@code content} of a
   * {@code <meta http-equiv="refresh">} is FORBIDDEN: its {@code url=} part navigates the page.
   * The {@code type} of a {@code <script>} is FORBIDDEN: it decides whether the content is
   * JavaScript. The {@code http-equiv} of a {@code <meta>} is FORBIDDEN: it decides whether
   * {@code content} is a refresh URL.
   */
  public static OutputContext attribute(Element element, String attributeName) {
    if (attributeName != null && isFrameworkDirective(element, attributeName)) {
      return OutputContext.EVENT_HANDLER;
    }
    if (attributeName == null || !PLAIN_NAME.matcher(attributeName).matches()) {
      return OutputContext.FORBIDDEN;
    }
    String el = element.name().toLowerCase(Locale.ROOT);
    String attr = attributeName.toLowerCase(Locale.ROOT);
    if (attr.startsWith("on")) {
      return OutputContext.EVENT_HANDLER;
    }
    if (attr.equals("style")) {
      return OutputContext.STYLE;
    }
    if (attr.equals("srcdoc")
        || isRefreshContent(element, el, attr)
        || (el.equals("script") && attr.equals("type"))
        || (el.equals("meta") && attr.equals("http-equiv"))) {
      return OutputContext.FORBIDDEN;
    }
    if (isResourceUrl(el, attr)) {
      return OutputContext.RESOURCE_URL;
    }
    if (URL_ATTRIBUTES.contains(attr) || isAnimationValue(el, attr)) {
      return OutputContext.URL;
    }
    return OutputContext.ATTRIBUTE;
  }

  /** htmx attributes ({@code hx-} or {@code data-hx-}) whose value is evaluated as JavaScript. */
  private static final Set<String> HTMX_SCRIPT_ATTRIBUTES =
      Backports.setOf("hx-vals", "hx-vars", "hx-headers", "hx-request", "hx-trigger");

  /**
   * Whether {@code attributeName} is a client-side framework attribute whose value the framework
   * evaluates as a JavaScript expression (#235). Attribute escaping does not help there: once the
   * framework mounts the element, the decoded value runs as code. Case-insensitive. The list:
   *
   * <ul>
   *   <li>Vue 2/3 and petite-vue: every {@code v-*} (built-in and custom directives such as
   *       {@code v-bind:x}, {@code v-on:x}, {@code v-model}, {@code v-if}, {@code v-for},
   *       {@code v-html}, {@code v-slot}), and the shorthands {@code :x} ({@code v-bind}),
   *       {@code @x} ({@code v-on}), {@code #x} ({@code v-slot}, whose value is a destructuring
   *       pattern with default expressions) and {@code .x} ({@code v-bind.prop}).
   *   <li>Alpine.js: every {@code x-*} ({@code x-data}, {@code x-init}, {@code x-on:x},
   *       {@code x-bind:x}, {@code x-html}, ...), plus the {@code :x} and {@code @x} shorthands.
   *   <li>htmx ({@code hx-} or {@code data-hx-}): {@code hx-on*} ({@code hx-on:click},
   *       {@code hx-on::after-request}, {@code hx-on-click}, the old {@code hx-on}),
   *       {@code hx-vals}, {@code hx-headers} and {@code hx-request} (a {@code js:} prefix
   *       evaluates the rest), {@code hx-vars} (always evaluated) and {@code hx-trigger} (its
   *       {@code [filter]} is evaluated).
   *   <li>AngularJS: every {@code ng-*}, {@code ng:*}, {@code ng_*}, also with a {@code data-}
   *       or {@code x-} prefix.
   *   <li>Knockout: {@code data-bind}, and {@code params} on a custom element (a name with a
   *       {@code -}), which Knockout components evaluate as a binding.
   * </ul>
   *
   * Other {@code data-*} attributes, and the htmx attributes that hold URLs, selectors or plain
   * strings ({@code hx-get}, {@code hx-target}, {@code hx-swap}, ...), stay ordinary attributes.
   */
  public static boolean isFrameworkDirective(Element element, String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    if (a.equals("params") && element.name().indexOf('-') >= 0) {
      return true;
    }
    if (a.startsWith(":") || a.startsWith("@") || a.startsWith("#") || a.startsWith(".")
        || a.startsWith("v-") || a.startsWith("x-") || a.equals("data-bind")) {
      return true;
    }
    String unprefixed = a.startsWith("data-") ? a.substring("data-".length()) : a;
    return unprefixed.startsWith("hx-on")
        || HTMX_SCRIPT_ATTRIBUTES.contains(unprefixed)
        || unprefixed.startsWith("ng-")
        || unprefixed.startsWith("ng:")
        || unprefixed.startsWith("ng_");
  }

  /** {@code content} on a {@code <meta>} whose static {@code http-equiv} is {@code refresh}. */
  private static boolean isRefreshContent(Element element, String el, String attr) {
    if (!attr.equals("content") || !el.equals("meta")) {
      return false;
    }
    for (Attribute a : element.attributes()) {
      if (!a.isTessera()
          && a.name().equalsIgnoreCase("http-equiv")
          && a.value() != null
          && Backports.strip(a.value()).equalsIgnoreCase("refresh")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAnimationValue(String el, String attr) {
    return (el.equals("animate") || el.equals("set"))
        && (attr.equals("values") || attr.equals("to") || attr.equals("from") || attr.equals("by"));
  }

  private static boolean isResourceUrl(String element, String attr) {
    switch (element) {
      case "script": // SVG <script> loads code from href / xlink:href.
        return attr.equals("src") || attr.equals("href") || attr.equals("xlink:href");
      case "iframe":
      case "frame":
      case "embed":
        return attr.equals("src");
      case "object":
        return attr.equals("data");
      case "base":
      case "link":
        return attr.equals("href");
      default:
        return false;
    }
  }

  /**
   * Context for text directly inside {@code element}: SCRIPT
   * inside {@code <script>} with no type or a JavaScript/module/JSON type, FORBIDDEN inside
   * other script types, STYLE inside {@code <style>}, otherwise TEXT.
   *
   * <p>A {@code <script>} whose type is set by a {@code t:type} attribute is FORBIDDEN: the
   * content type must be known at build time.
   */
  public static OutputContext text(Element element) {
    switch (element.name().toLowerCase(Locale.ROOT)) {
      case "script":
        return scriptContext(element);
      case "style":
        return OutputContext.STYLE;
      default:
        return OutputContext.TEXT;
    }
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
    return SCRIPT_TYPES.contains(Backports.strip(type).toLowerCase(Locale.ROOT))
        ? OutputContext.SCRIPT
        : OutputContext.FORBIDDEN;
  }
}
