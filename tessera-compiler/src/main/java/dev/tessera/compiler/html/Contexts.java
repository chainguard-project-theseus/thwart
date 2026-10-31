package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
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

  /** The {@link #SCRIPT_TYPES} whose content is JSON data, not JavaScript. */
  private static final Set<String> JSON_SCRIPT_TYPES = Backports.setOf("application/json", "application/ld+json");

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
   * {@code <set>} ignores {@code values from by}, but they are checked the same way. When the
   * static {@code attributeName} of an animation element names one of those SVG attributes
   * ({@code fill}, {@code mask}, ...), its {@code values to from by} are STYLE instead, and the
   * {@code attributeName} of an animation element is FORBIDDEN: a computed one could retarget the
   * values from a URL-checked attribute to CSS. The {@code content} of a
   * {@code <meta http-equiv="refresh">} is REFRESH: its {@code url=} part navigates the page, so
   * only a same-origin link is allowed there (#237).
   * The {@code type} of a {@code <script>} is FORBIDDEN: it decides whether the content is
   * JavaScript. The {@code http-equiv} of a {@code <meta>} is FORBIDDEN: it decides whether
   * {@code content} is a refresh URL. The {@link #isHtmxRequestUrl htmx request URLs}
   * ({@code hx-get}, {@code hx-post}, ...) are RESOURCE_URL: htmx fetches them and swaps the
   * response into the page as HTML (#249). {@code hx-push-url} and {@code hx-replace-url} only
   * change the address bar and are URL. {@link #isHtmxBoost hx-boost} is FORBIDDEN: it decides
   * which form actions htmx requests (#258). The {@link #isTreeBuilderInput attributes the tree
   * builder reads} ({@code is}, {@code xmlns}, a {@code <meta>}'s {@code charset}, ...) are FORBIDDEN.
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
    String el = HtmlParser.asciiLower(element.name());
    String attr = HtmlParser.asciiLower(attributeName);
    if (attr.startsWith("on")) {
      return OutputContext.EVENT_HANDLER;
    }
    if (attr.equals("style") || isSvgCssUrl(element, el, attr) || animatesSvgCssUrl(element, el, attr)) {
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
        || (el.equals("meta") && attr.equals("http-equiv"))
        || (TIMED_ANIMATIONS.contains(el) && attr.equals("attributename"))
        || isTreeBuilderInput(element, attributeName)) {
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
    String a = HtmlParser.asciiLower(attributeName);
    return HTMX_REQUEST_ATTRIBUTES.contains(a.startsWith("data-") ? a.substring("data-".length()) : a);
  }

  /**
   * Whether {@code attributeName} is htmx 1.x's {@code hx-sse} or {@code hx-ws} (also with
   * {@code data-}), whose {@code connect:<url>} htmx connects to and swaps into the page. They
   * are FORBIDDEN: the URL sits inside a mini-language, so it can only be written statically.
   * Case-insensitive.
   */
  public static boolean isLegacyHtmxConnect(String attributeName) {
    String a = HtmlParser.asciiLower(attributeName);
    String unprefixed = a.startsWith("data-") ? a.substring("data-".length()) : a;
    return unprefixed.equals("hx-sse") || unprefixed.equals("hx-ws");
  }

  /**
   * Whether {@code attributeName} is {@code hx-boost} or {@code data-hx-boost} (#258). It is
   * FORBIDDEN: it decides whether htmx submits the forms below with AJAX and swaps the response
   * into the page, so the compiler must know it to check their actions. Case-insensitive.
   */
  public static boolean isHtmxBoost(String attributeName) {
    String a = HtmlParser.asciiLower(attributeName);
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
        String v = HtmlParser.asciiLower(a.value());
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
    String a = HtmlParser.asciiLower(attributeName);
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
    String el = HtmlParser.asciiLower(element.name());
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
   *   <li>AngularJS: every {@code ng-*}, {@code ng:*}, {@code ng_*}, also after one prefix
   *       {@code x} or {@code data} followed by {@code :}, {@code -} or {@code _}
   *       and after a run of those separators ({@code data:ng-click}, {@code x_ng-click},
   *       {@code data--ng-click}, {@code _ng-click}, ...).
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
    String a = HtmlParser.asciiLower(attributeName);
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
        || isAngularDirective(attributeName);
  }

  /**
   * Whether AngularJS reads {@code attributeName} as an {@code ng} directive. Its
   * {@code directiveNormalize} (angular.js v1.8.3 src/ng/compile.js lines 4247-4260) strips one
   * {@code x} or {@code data} prefix followed by {@code :}, {@code -} or {@code _}
   * ({@code PREFIX_REGEXP = /^((?:x|data)[:\-_])/i}), then drops a leading run of those
   * separators and camel-cases at every later run ({@code SPECIAL_CHARS_REGEXP}), so
   * {@code data:ng-click}, {@code x_ng-click}, {@code data--ng-click} and {@code _ng-click} are
   * all {@code ngClick}. A name is a directive when, after that prefix and run, it starts with
   * {@code ng} and a separator. Only A-Z are folded: AngularJS lower-cases the whole name first,
   * but no other character lower-cases to a letter of these prefixes.
   */
  private static boolean isAngularDirective(String attributeName) {
    String a = HtmlParser.asciiLower(attributeName);
    int i = a.startsWith("x") ? 1 : a.startsWith("data") ? 4 : 0;
    i = i > 0 && i < a.length() && isAngularSeparator(a.charAt(i)) ? i + 1 : 0;
    while (i < a.length() && isAngularSeparator(a.charAt(i))) {
      i++;
    }
    return a.startsWith("ng", i) && i + 2 < a.length() && isAngularSeparator(a.charAt(i + 2));
  }

  private static boolean isAngularSeparator(char c) {
    return c == ':' || c == '-' || c == '_';
  }

  /**
   * {@code content} on a {@code <meta>} whose static {@code http-equiv} is {@code refresh}. The name and value fold
   * A-Z only, as in a browser: {@code http-equ\u0131v} is not {@code http-equiv}.
   */
  private static boolean isRefreshContent(Element element, String el, String attr) {
    return attr.equals("content") && el.equals("meta") && hasHttpEquiv(element, "refresh");
  }

  /**
   * Whether {@code element} has a static {@code http-equiv} equal to the lower-case {@code value},
   * folding A-Z only after trimming whitespace.
   */
  private static boolean hasHttpEquiv(Element element, String value) {
    for (Attribute a : element.attributes()) {
      if (!a.isTessera()
          && HtmlParser.asciiLower(a.name()).equals("http-equiv")
          && a.value() != null
          && HtmlParser.asciiLower(HtmlParser.stripAnySpace(a.value())).equals(value)) {
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

  /** The attributes of an SVG animation element that hold the values it sets. */
  private static final Set<String> ANIMATION_VALUES = Backports.setOf("values", "to", "from", "by");

  /**
   * Whether {@code attr} on {@code element} is a value ({@code values to from by}) of a
   * {@link #TIMED_ANIMATIONS timed animation element} whose static {@code attributeName} names one
   * of the {@link #SVG_CSS_URL_ATTRIBUTES}: the animation sets that CSS, so the value is CSS too.
   * The {@code attributeName} attribute is found folding A-Z only, as in a browser: {@code
   * attr\u0131buteName} is not {@code attributeName}, so the browser animates what the real one names.
   * The name it holds is compared stripped and ASCII case-insensitively, after any {@code prefix:},
   * which fails closed for names a browser would not match. A {@code values} list is CSS as a
   * whole: a {@code CssValue} cannot start a {@code url(} after a {@code ;} (it percent-encodes
   * {@code (}). The {@code attributeName} itself cannot be computed ({@link #attribute}).
   */
  private static boolean animatesSvgCssUrl(Element element, String el, String attr) {
    if (!TIMED_ANIMATIONS.contains(el) || !ANIMATION_VALUES.contains(attr)) {
      return false;
    }
    for (Attribute a : element.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiLower(a.name()).equals("attributename") && a.value() != null) {
        String target = HtmlParser.asciiLower(HtmlParser.stripAnySpace(a.value()));
        if (SVG_CSS_URL_ATTRIBUTES.contains(target.substring(target.lastIndexOf(':') + 1))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether the HTML tree builder reads attribute {@code attributeName} of {@code element} while it
   * builds the page (HTML 13.2.6), so a computed value is FORBIDDEN: {@code is} (which custom
   * element to create), {@code xmlns} and {@code xmlns:xlink} (checked against the namespace) on any
   * element; a {@code <template>}'s {@code shadowroot*} attributes (a declarative shadow root) and
   * {@code for}; a {@code <meta>}'s {@code charset}, and its {@code content} when its static
   * {@code http-equiv} is {@code Content-Type} (the encoding the page is read in; a computed
   * {@code http-equiv} is FORBIDDEN itself). Case-insensitive.
   */
  public static boolean isTreeBuilderInput(Element element, String attributeName) {
    String el = HtmlParser.asciiLower(element.name());
    String attr = HtmlParser.asciiLower(attributeName);
    if (attr.equals("is") || attr.equals("xmlns") || attr.equals("xmlns:xlink")) {
      return true;
    }
    if (el.equals("template")) {
      return attr.startsWith("shadowroot") || attr.equals("for");
    }
    return el.equals("meta")
        && (attr.equals("charset") || (attr.equals("content") && hasHttpEquiv(element, "content-type")));
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
    return BROWSER_RAW_TEXT.contains(HtmlParser.asciiLower(name));
  }

  /**
   * Context for text directly inside {@code element}: SCRIPT
   * inside {@code <script>} with no type or a JavaScript/module/JSON type, FORBIDDEN inside
   * other script types, STYLE inside {@code <style>}, otherwise TEXT. A TEXT hole is escaped for
   * the parser state the verifier finds there: {@code rcdata} in the text of a {@code <title>} or
   * {@code <textarea>}, {@code text} elsewhere (#378, {@code ContextVerifier}).
   *
   * <p>A {@code <script>} whose type is set by a {@code t:type} attribute is FORBIDDEN: the
   * content type must be known at build time.
   */
  public static OutputContext text(Element element) {
    switch (HtmlParser.asciiLower(element.name())) {
      case "script":
        return scriptContext(element);
      case "style":
        return OutputContext.STYLE;
      default:
        return OutputContext.TEXT;
    }
  }

  /**
   * Whether {@code element} is a {@code <script>} whose content is JSON data
   * ({@code type="application/json"} or {@code "application/ld+json"}), not JavaScript.
   */
  public static boolean isJsonScript(Element element) {
    if (!HtmlParser.asciiEqualsIgnoreCase(element.name(), "script")) {
      return false;
    }
    String type = null;
    for (Attribute a : element.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "type")) {
        type = a.value() == null ? "" : a.value();
      }
    }
    return type != null && JSON_SCRIPT_TYPES.contains(HtmlParser.asciiLower(HtmlParser.stripAnySpace(type)));
  }

  private static OutputContext scriptContext(Element script) {
    String type = null;
    for (Attribute a : script.attributes()) {
      if (a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.localName(), "type")) {
        return OutputContext.FORBIDDEN;
      }
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "type")) {
        type = a.value() == null ? "" : a.value();
      }
    }
    if (type == null) {
      return OutputContext.SCRIPT;
    }
    return SCRIPT_TYPES.contains(HtmlParser.asciiLower(HtmlParser.stripAnySpace(type)))
        ? OutputContext.SCRIPT
        : OutputContext.FORBIDDEN;
  }
}
