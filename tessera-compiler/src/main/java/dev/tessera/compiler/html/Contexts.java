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

  private static final Set<String> BROWSER_RAW_TEXT =
      Backports.setOf("xmp", "iframe", "noembed", "noframes", "noscript");

  private static final Set<String> URL_ATTRIBUTES =
      Backports.setOf(
          "href", "src", "action", "formaction", "cite", "poster", "background", "longdesc",
          "usemap", "ping", "manifest", "srcset", "imagesrcset", "xlink:href");

  /**
   * SVG presentation attributes whose value is CSS that may hold a {@code url()}: a paint server
   * ({@code fill}, {@code stroke}), a reference ({@code clip-path}, {@code mask}, {@code filter},
   * {@code marker-*}) or a cursor image. A browser reads them as the CSS property of the same name.
   * The {@code marker} shorthand is not a presentation attribute (SVG 2 section 6.6) but is
   * animatable (SVG 1.1 section 11.6.3), so its animations set CSS too; as an attribute it fails closed.
   */
  private static final Set<String> SVG_CSS_URL_ATTRIBUTES =
      Backports.setOf(
          "fill", "stroke", "clip-path", "mask", "filter", "marker-start", "marker-mid", "marker-end", "marker", "cursor");

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
   * {@code element}: {@link #isJsonBinding JSON bindings} ({@code :x}, {@code v-bind:x},
   * {@code x-bind:x} with an ordinary target {@code x}) are SCRIPT_ATTRIBUTE; other
   * {@link #isFrameworkDirective framework directives} and {@code on*} are EVENT_HANDLER, {@code style} and
   * the SVG presentation attributes that take a CSS {@code url()} ({@code fill}, {@code mask}, ...)
   * are STYLE, {@code srcdoc} and names that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*} are FORBIDDEN, resource URLs are
   * RESOURCE_URL, other URL attributes are URL, other {@link #isKnownName known names} ATTRIBUTE, and
   * every other name FORBIDDEN (#330). The {@code to from by}
   * of an SVG {@code <animate>} and the {@code to} of a {@code <set>} are URL too: they can set an
   * {@code href} to {@code javascript:}. The {@code values} of an {@code <animate>} is URL_LIST: a
   * {@code ;}-separated list whose items SVG applies in turn, so every item is checked (#284).
   * {@code <set>} ignores {@code values from by}, but they are checked the same way. The
   * {@code content} of a {@code <meta http-equiv="refresh">} is REFRESH: its {@code url=} part
   * navigates the page, so only a same-origin link is allowed there (#237).
   * The {@code type} of a {@code <script>} is FORBIDDEN: it decides whether the content is
   * JavaScript. The {@code http-equiv} of a {@code <meta>} is FORBIDDEN: it decides whether
   * {@code content} is a refresh URL. The {@link #isHtmxRequestUrl htmx request URLs}
   * ({@code hx-get}, {@code hx-post}, ...) are RESOURCE_URL: htmx fetches them and swaps the
   * response into the page as HTML (#249). {@code hx-push-url} and {@code hx-replace-url} only
   * change the address bar and are URL. {@link #isHtmxBoost hx-boost} is FORBIDDEN: it decides
   * which form actions htmx requests (#258).
   */
  public static OutputContext attribute(Element element, String attributeName) {
    return attribute(element, attributeName, true);
  }

  /** {@link #attribute(Element, String)}, where {@code knownNames} false accepts any name as ATTRIBUTE. */
  private static OutputContext attribute(Element element, String attributeName, boolean knownNames) {
    if (attributeName != null && isJsonBinding(element, attributeName)) {
      return OutputContext.SCRIPT_ATTRIBUTE;
    }
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
    if (attr.equals("style") || isSvgCssUrl(element, el, attr)) {
      return OutputContext.STYLE;
    }
    if (isHtmxRequestUrl(attr)) {
      return OutputContext.RESOURCE_URL;
    }
    if (isLegacyHtmxConnect(attr) || isHtmxBoost(attr)) {
      return OutputContext.FORBIDDEN;
    }
    String unprefixed = attr.startsWith("data-") ? attr.substring("data-".length()) : attr;
    if (unprefixed.equals("hx-push-url") || unprefixed.equals("hx-replace-url")) {
      return OutputContext.URL;
    }
    if (isRefreshContent(element, el, attr)) {
      return OutputContext.REFRESH;
    }
    if (attr.equals("srcdoc")
        || (el.equals("script") && attr.equals("type"))
        || (el.equals("meta") && attr.equals("http-equiv"))) {
      return OutputContext.FORBIDDEN;
    }
    if (isResourceUrl(el, attr)) {
      return OutputContext.RESOURCE_URL;
    }
    if (isAnimation(el) && attr.equals("values")) {
      return OutputContext.URL_LIST;
    }
    if (URL_ATTRIBUTES.contains(attr)
        || (isAnimation(el) && (attr.equals("to") || attr.equals("from") || attr.equals("by")))) {
      return OutputContext.URL;
    }
    return !knownNames || KnownAttributes.accepts(element, attr) ? OutputContext.ATTRIBUTE : OutputContext.FORBIDDEN;
  }

  /**
   * Whether {@code attributeName} may be computed on {@code element} as far as its name goes
   * (#330, security-architecture.md section 5.6): a standard HTML, SVG, MathML or ARIA attribute,
   * a {@code data-*} name, or a plain {@code [a-z][a-z0-9]*} name on a custom element (a name with
   * a {@code -}). The framework names {@link #attribute} classifies (directives, htmx URLs, ...)
   * are the framework table. {@link #attribute} decides whether a known name may hold an
   * expression. Case-insensitive.
   */
  public static boolean isKnownName(Element element, String attributeName) {
    return KnownAttributes.accepts(element, attributeName);
  }

  /**
   * Whether {@code attributeName} is FORBIDDEN on {@code element} only because its name is not
   * known (#330): it is a well-formed name that no framework rule classifies and that is not
   * {@link #isKnownName known}, such as {@code up-on-loaded}, {@code hx-action} or {@code foo}.
   */
  public static boolean isUnknownName(Element element, String attributeName) {
    return attributeName != null
        && attribute(element, attributeName, false) == OutputContext.ATTRIBUTE
        && !KnownAttributes.accepts(element, attributeName);
  }

  /**
   * htmx attributes (also with a {@code data-} prefix) whose URL htmx requests and swaps into the
   * page as HTML: the request verbs, and the {@code sse} and {@code ws} extensions' connect URLs.
   */
  private static final Set<String> HTMX_REQUEST_ATTRIBUTES =
      Backports.setOf("hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete", "sse-connect", "ws-connect");

  /**
   * Whether {@code attributeName} is an htmx request URL (#249): {@code hx-get}, {@code hx-post},
   * {@code hx-put}, {@code hx-patch}, {@code hx-delete}, {@code sse-connect} or
   * {@code ws-connect}, also with a {@code data-} prefix. htmx fetches the URL and swaps the
   * response into the page as HTML (htmx 1.x also cross-origin), so a computed value must be a
   * same-origin link. Case-insensitive.
   */
  public static boolean isHtmxRequestUrl(String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    return HTMX_REQUEST_ATTRIBUTES.contains(a.startsWith("data-") ? a.substring("data-".length()) : a);
  }

  /**
   * Whether {@code attributeName} is htmx 1.x's {@code hx-sse} or {@code hx-ws} (also with
   * {@code data-}), whose {@code connect:<url>} htmx connects to and swaps into the page. They
   * are FORBIDDEN: the URL sits inside a mini-language, so it can only be written statically.
   * Case-insensitive.
   */
  public static boolean isLegacyHtmxConnect(String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    String unprefixed = a.startsWith("data-") ? a.substring("data-".length()) : a;
    return unprefixed.equals("hx-sse") || unprefixed.equals("hx-ws");
  }

  /**
   * Whether {@code attributeName} is {@code hx-boost} or {@code data-hx-boost} (#258). It is
   * FORBIDDEN: it decides whether htmx submits the forms below with AJAX and swaps the response
   * into the page, so the compiler must know it to check their actions. Case-insensitive.
   */
  public static boolean isHtmxBoost(String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    return a.equals("hx-boost") || a.equals("data-hx-boost");
  }

  /**
   * The static {@code hx-boost} / {@code data-hx-boost} of {@code element} (#258): null if it has
   * neither, false if every one present is exactly {@code false} (htmx's opt-out), true otherwise.
   * htmx boosts only on exactly {@code true}; any other value counting as boosted only adds checks.
   */
  public static Boolean staticHtmxBoost(Element element) {
    Boolean boost = null;
    for (Attribute a : element.attributes()) {
      if (!a.isTessera() && isHtmxBoost(a.name())) {
        boost = (boost != null && boost) || !"false".equals(a.value());
      }
    }
    return boost;
  }

  /**
   * Whether {@code element} has an {@link #isHtmxRequestUrl htmx request attribute}, static,
   * {@code t:} or (by name anywhere in its value) {@code t:attr} (#258): on a {@code <form>}, htmx
   * then requests a submit button's {@code formaction} instead of that URL.
   */
  public static boolean hasHtmxRequestAttribute(Element element) {
    for (Attribute a : element.attributes()) {
      if (isHtmxRequestUrl(a.localName())) {
        return true;
      }
      if (a.name().equals(Attribute.PREFIX + "attr") && a.value() != null) {
        String v = a.value().toLowerCase(Locale.ROOT);
        for (String name : HTMX_REQUEST_ATTRIBUTES) {
          if (v.contains(name)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** A bind target: a plain attribute name without modifiers ({@code .}) or namespaces ({@code :}). */
  private static final Pattern BIND_TARGET = Pattern.compile("[a-z_][a-z0-9_-]*");

  /** Bind prefixes: Vue {@code v-bind:}, Alpine {@code x-bind:}, and their shared {@code :} shorthand. */
  private static final String[] BIND_PREFIXES = {"v-bind:", "x-bind:", ":"};

  /**
   * Targets that are ordinary attributes but, set as DOM properties (Vue 3 sets {@code key in el}
   * as a property), write markup ({@code innerHTML}, {@code outerHTML}) or rewrite a link's URL
   * ({@code <a>}/{@code <area>} {@code protocol}, {@code host}, ...: {@code :protocol} turns
   * {@code mailto:alert(1)} into {@code javascript:alert(1)}), or (Vue {@code is}) pick the
   * component or element to render. Compared without {@code -}: a Vue custom element camelizes
   * {@code inner-h-t-m-l} to {@code innerHTML}. Denied on every element, since a component's
   * root element is not known.
   */
  private static final Set<String> BIND_DENIED_TARGETS =
      Backports.setOf(
          "innerhtml", "outerhtml", "is", "protocol", "host", "hostname", "port", "pathname", "search",
          "hash", "username", "password");

  /**
   * Whether {@code attributeName} is a Vue or Alpine binding whose value may be a JSON value (#246):
   * {@code v-bind:x}, {@code x-bind:x} or {@code :x} where the target {@code x}
   *
   * <ul>
   *   <li>matches {@code [a-z_][a-z0-9_-]*} (case-insensitive): no modifier such as {@code .prop}
   *       or {@code .camel}, no dynamic argument {@code [key]}, no namespace;
   *   <li>is itself an ordinary attribute ({@link #attribute} is ATTRIBUTE on this element): not
   *       {@code on*}, {@code style}, a URL, a resource URL, {@code srcdoc}, a framework directive
   *       or otherwise forbidden, since the framework sets the target to the value;
   *   <li>is not {@code innerHTML}, {@code outerHTML}, {@code is} or a URL part such as
   *       {@code protocol} or {@code host};
   * </ul>
   *
   * and the element is not {@code <script>}, {@code <style>} or {@code <meta>}. The framework
   * evaluates the value as JavaScript, so it is written as JSON (OutputContext.SCRIPT_ATTRIBUTE).
   * Everything else, including {@code v-bind="obj"} and {@code x-bind="obj"} with no target and
   * the {@code .x} shorthand, stays a {@link #isFrameworkDirective directive} (EVENT_HANDLER).
   */
  public static boolean isJsonBinding(Element element, String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    String target = null;
    for (String prefix : BIND_PREFIXES) {
      if (a.startsWith(prefix)) {
        target = a.substring(prefix.length());
        break;
      }
    }
    if (target == null || !BIND_TARGET.matcher(target).matches()
        || BIND_DENIED_TARGETS.contains(target.replace("-", ""))) {
      return false;
    }
    String el = element.name().toLowerCase(Locale.ROOT);
    if (el.equals("script") || el.equals("style") || el.equals("meta")) {
      return false;
    }
    // TODO(#333): bind targets become an allow-list; until then any ordinary name, known or not.
    return attribute(element, target, false) == OutputContext.ATTRIBUTE;
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
   *   <li>Vue 2/3 and petite-vue: every {@code v-*} (the {@link #isJsonBinding JSON bindings} are
   *       SCRIPT_ATTRIBUTE instead) (built-in and custom directives such as
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
   *   <li>_hyperscript: {@code _}, {@code script} and {@code data-script}, whose value is
   *       hyperscript code (#249).
   * </ul>
   *
   * Other {@code data-*} attributes, and the htmx attributes that hold selectors or plain
   * strings ({@code hx-target}, {@code hx-swap}, ...), stay ordinary attributes; htmx request
   * URLs are {@link #isHtmxRequestUrl RESOURCE_URL}. Frameworks configured to read other
   * attribute names (an Alpine prefix, a Knockout binding provider, a hyperscript
   * {@code attributes} setting) are not detected.
   */
  public static boolean isFrameworkDirective(Element element, String attributeName) {
    String a = attributeName.toLowerCase(Locale.ROOT);
    if (a.equals("params") && element.name().indexOf('-') >= 0) {
      return true;
    }
    if (a.startsWith(":") || a.startsWith("@") || a.startsWith("#") || a.startsWith(".")
        || a.startsWith("v-") || a.startsWith("x-") || a.equals("data-bind")
        || a.equals("_") || a.equals("script") || a.equals("data-script")) {
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

  /** SVG animation elements, lower-cased, whose {@code fill} is a timing attribute. */
  private static final Set<String> TIMED_ANIMATIONS =
      Backports.setOf("animate", "set", "animatemotion", "animatetransform", "animatecolor");

  /**
   * Whether {@code attr} on {@code element} is an SVG presentation attribute whose CSS value may
   * hold a {@code url()} ({@link #SVG_CSS_URL_ATTRIBUTES}): on a {@link Element#foreign foreign}
   * element (SVG, and also MathML, which has no such attributes: failing closed there is harmless),
   * except {@code fill} on a {@link #TIMED_ANIMATIONS timed animation element}, where it is the
   * timing attribute ({@code freeze}, {@code remove}).
   */
  private static boolean isSvgCssUrl(Element element, String el, String attr) {
    return element.foreign() && SVG_CSS_URL_ATTRIBUTES.contains(attr)
        && !(attr.equals("fill") && TIMED_ANIMATIONS.contains(el));
  }

  private static boolean isAnimation(String el) {
    return el.equals("animate") || el.equals("set");
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
   * Whether a browser reads the content of element {@code name} as raw text while Tessera parses
   * it as markup: {@code xmp iframe noembed noframes}, and {@code noscript} when scripting is
   * on. Holes inside are escaped for TEXT, which is inert in raw text too, so the two readings
   * agree as long as the browser ends the element where Tessera does: at the first
   * {@code </name} after the start tag.
   */
  public static boolean browserRawText(String name) {
    return BROWSER_RAW_TEXT.contains(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Context for text directly inside {@code element}: SCRIPT
   * inside {@code <script>} with no type or a JavaScript/module/JSON type, FORBIDDEN inside
   * other script types, STYLE inside {@code <style>}, RCDATA inside {@code <title>} and
   * {@code <textarea>} (#248), otherwise TEXT. A {@link Element#foreign foreign} {@code <title>} or
   * {@code <textarea>} (inside {@code <svg>} or {@code <math>}) is TEXT: a browser parses its content
   * as markup, and frameworks interpolate {@code {{ }}} in it (#277).
   *
   * <p>A {@code <script>} whose type is set by a {@code t:type} attribute is FORBIDDEN: the
   * content type must be known at build time.
   *
   * <p>The verifier checks the TEXT/RCDATA choice against the parser state ({@code Rules.hole}).
   */
  // TODO(#378): choose TEXT or RCDATA from the parser state. The generated code is written before
  // the verifier runs, so the name decides for now (every fragment has a state since #461).
  public static OutputContext text(Element element) {
    switch (element.name().toLowerCase(Locale.ROOT)) {
      case "script":
        return scriptContext(element);
      case "style":
        return OutputContext.STYLE;
      case "title":
      case "textarea":
        return element.foreign() ? OutputContext.TEXT : OutputContext.RCDATA;
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
