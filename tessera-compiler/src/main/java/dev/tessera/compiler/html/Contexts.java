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

  /**
   * The attributes whose grammar is exactly one URL (#539): WHATWG "valid (non-empty) URL
   * potentially surrounded by spaces" ({@code href src action formaction cite poster}), the
   * obsolete {@code background longdesc manifest}, and SVG's {@code xlink:href}. Only these take
   * the URL escaper, which checks the whole value as one URL.
   */
  private static final Set<String> URL_ATTRIBUTES =
      Backports.setOf(
          "href", "src", "action", "formaction", "cite", "poster", "background", "longdesc", "manifest",
          "xlink:href");

  /**
   * The attributes that hold a list of URLs (#414, #539): {@code ping} is space-separated URLs,
   * {@code srcset} and {@code imagesrcset} comma-separated image candidates. TRUSTED_URL.
   */
  private static final Set<String> URL_LIST_ATTRIBUTES = Backports.setOf("ping", "srcset", "imagesrcset");

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
   * {@code element} (#330; the table is docs/context-model.md section 6.1). In order:
   * {@link #isJsonBinding JSON bindings} are SCRIPT_ATTRIBUTE, other {@link #isFrameworkDirective
   * framework directives} EVENT_HANDLER; names that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*}
   * FORBIDDEN; {@code on*} EVENT_HANDLER; {@code style} and the {@link #isSvgCssUrl SVG
   * attributes that take a CSS url()} (also {@link #animatesSvgCssUrl animated}) STYLE;
   * {@link #isHtmxRequestUrl htmx request URLs} RESOURCE_URL, {@link #isLegacyHtmxConnect hx-ws,
   * hx-sse} and {@link #isHtmxBoost hx-boost} FORBIDDEN, {@code hx-push-url hx-replace-url} URL; a
   * refresh {@code content} REFRESH ({@link OutputContext#REFRESH}); {@code srcdoc}, the attributes
   * that decide how another value is read and {@link #isTreeBuilderInput tree-builder inputs}
   * FORBIDDEN; {@link #isResourceUrl resource URLs} RESOURCE_URL; {@link #URL_LIST_ATTRIBUTES URL
   * lists} and {@link #ANIMATION_VALUES animation values} TRUSTED_URL; {@link #URL_ATTRIBUTES} URL;
   * other standard names that are not inert ({@code inert-attributes.txt}) and a {@code content}
   * of a {@code <meta>} that does not {@link #isNamedMetaContent name plain metadata}
   * TYPED_ATTRIBUTE; other {@link KnownAttributes#accepts known names} ATTRIBUTE; and every other
   * name FORBIDDEN.
   */
  public static OutputContext attribute(Element element, String attributeName) {
    return attribute(element, attributeName, true);
  }

  /** {@link #attribute(Element, String)}, where {@code knownNames} false accepts any name as ATTRIBUTE. */
  private static OutputContext attribute(Element element, String attributeName, boolean knownNames) {
    if (attributeName == null) {
      return OutputContext.FORBIDDEN;
    }
    if (isJsonBinding(element, attributeName)) {
      return OutputContext.SCRIPT_ATTRIBUTE;
    }
    if (isFrameworkDirective(element, attributeName)) {
      return OutputContext.EVENT_HANDLER;
    }
    if (!PLAIN_NAME.matcher(attributeName).matches()) {
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
    // TODO(#584): the neutral-value rule replaces the htmx name classification here.
    if (isLegacyHtmxConnect(attr) || isHtmxBoost(attr)) {
      return OutputContext.FORBIDDEN;
    }
    String htmx = unprefixed(attr);
    if (htmx.equals("hx-push-url") || htmx.equals("hx-replace-url")) {
      return OutputContext.URL;
    }
    if (isRefreshContent(element, el, attr)) {
      return OutputContext.REFRESH;
    }
    // A <script>'s type decides whether its content is JavaScript, a <meta>'s http-equiv whether
    // content is a refresh URL, an animation's attributeName whether values, to, from, by are CSS.
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
    if (URL_LIST_ATTRIBUTES.contains(attr) || (isAnimation(el) && ANIMATION_VALUES.contains(attr))) {
      return OutputContext.TRUSTED_URL;
    }
    if (URL_ATTRIBUTES.contains(attr)) {
      return OutputContext.URL;
    }
    if (KnownAttributes.needsSafeValue(element, attr)
        || (el.equals("meta") && attr.equals("content") && !isNamedMetaContent(element))) {
      return OutputContext.TYPED_ATTRIBUTE;
    }
    return !knownNames || KnownAttributes.accepts(element, attr) ? OutputContext.ATTRIBUTE : OutputContext.FORBIDDEN;
  }

  /**
   * Whether {@code attributeName} is FORBIDDEN on {@code element} only because its name is not
   * known (#330): it is a well-formed name that no framework rule classifies and that is not
   * {@link KnownAttributes#accepts known}, such as {@code up-on-loaded}, {@code hx-action} or {@code foo}.
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
    return HTMX_REQUEST_ATTRIBUTES.contains(unprefixed(HtmlParser.asciiLower(attributeName)));
  }

  /** {@code lowerName} without a leading {@code data-}, which htmx ignores. */
  private static String unprefixed(String lowerName) {
    return lowerName.startsWith("data-") ? lowerName.substring("data-".length()) : lowerName;
  }

  /**
   * Whether {@code attributeName} is htmx 1.x's {@code hx-sse} or {@code hx-ws} (also with
   * {@code data-}), whose URL sits inside a mini-language. A computed one is a build error (#562):
   * {@link #LEGACY_HTMX_CONNECT}. A static one is the author's markup: htmx behaviour is outside the
   * guarantee (#585). Case-insensitive.
   */
  public static boolean isLegacyHtmxConnect(String attributeName) {
    String unprefixed = unprefixed(HtmlParser.asciiLower(attributeName));
    return unprefixed.equals("hx-sse") || unprefixed.equals("hx-ws");
  }

  /** Why a computed {@link #isLegacyHtmxConnect hx-ws or hx-sse} is a build error, with the fix-it. */
  public static final String LEGACY_HTMX_CONNECT =
      "htmx 1.x's hx-ws and hx-sse are not supported; use htmx 2's ws-connect or sse-connect with a"
          + " same-origin URL such as /chat or @{/events}";

  /**
   * Whether {@code attributeName} is {@code hx-boost} or {@code data-hx-boost} (#258). A computed
   * one is FORBIDDEN: it decides whether htmx submits the forms below with AJAX. The compiler no
   * longer models boosting (#585); this classification goes with the neutral-value rule (#584).
   * Case-insensitive.
   */
  public static boolean isHtmxBoost(String attributeName) {
    return unprefixed(HtmlParser.asciiLower(attributeName)).equals("hx-boost");
  }

  /** A bind target: a plain attribute name without modifiers ({@code .}) or namespaces ({@code :}). */
  private static final Pattern BIND_TARGET = Pattern.compile("[a-z_][a-z0-9_-]*");

  /**
   * The attribute that makes Vue 3 and petite-vue skip an element and its content (#549). The
   * compiler adds it to a {@code <textarea>} or {@code <title>} with computed content, whose
   * RCDATA text can't break a value's {{ with a comment. Vue 3.5.44
   * {@code packages/compiler-core/src/parser.ts} lines 234-235 enter v-pre mode on it and line 112
   * keeps {{ }} as text there ({@code tokenizer.ts} line 457 for RCDATA); petite-vue 0.4.1
   * {@code src/walk.ts} lines 22-24 return before walking the element.
   */
  public static final String VUE_SKIP = "v-pre";

  /**
   * Whether {@code lowerName} (A-Z folded) is a Vue directive or binding, which {@link #VUE_SKIP}
   * turns off: every {@code v-*} and the shorthands {@code :x}, {@code @x}, {@code #x},
   * {@code .x}. Alpine's {@code x-*} is not one: Alpine ignores {@code v-pre}.
   */
  public static boolean isVueDirective(String lowerName) {
    return lowerName.startsWith("v-") || lowerName.startsWith(":") || lowerName.startsWith("@")
        || lowerName.startsWith("#") || lowerName.startsWith(".");
  }

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
   *   <li>AngularJS (not supported; a coarse deny): every name that {@link #isAngularDirective
   *       may be an ng directive}.
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
    if (isVueDirective(a) || a.startsWith("x-") || a.equals("data-bind")
        || a.equals("_") || a.equals("script") || a.equals("data-script")) {
      return true;
    }
    String unprefixed = unprefixed(a);
    return unprefixed.startsWith("hx-on")
        || HTMX_SCRIPT_ATTRIBUTES.contains(unprefixed)
        || isAngularDirective(attributeName);
  }

  /**
   * Whether {@code attributeName} may be an AngularJS {@code ng} directive. AngularJS is not
   * supported and its name rules are not modelled: a name is denied when, ignoring A-Z case,
   * {@code -}, {@code _} and {@code :}, it starts with {@code ng}, {@code xng} or {@code datang}.
   * That is a superset of every spelling AngularJS reads as a directive ({@code ng-click},
   * {@code data:ng-click}, {@code x_ng-click}, {@code _ng-click}, ...).
   */
  private static boolean isAngularDirective(String attributeName) {
    String a = HtmlParser.asciiLower(attributeName).replaceAll("[-_:]", "");
    return a.startsWith("ng") || a.startsWith("xng") || a.startsWith("datang");
  }

  /**
   * {@code content} on a {@code <meta>} whose static {@code http-equiv} is {@code refresh}. The name and value fold
   * A-Z only, as in a browser: {@code http-equ\u0131v} is not {@code http-equiv}.
   */
  private static boolean isRefreshContent(Element element, String el, String attr) {
    return attr.equals("content") && el.equals("meta") && hasHttpEquiv(element, "refresh");
  }

  /**
   * The {@code <meta name>} values whose {@code content} is display text no browser, OS or crawler
   * acts on (#590): a computed content is plain ATTRIBUTE only under one of these.
   */
  private static final Set<String> INERT_META_NAMES = Backports.setOf(
      "description", "keywords", "author", "application-name", "generator", "color-scheme", "theme-color");

  /**
   * The {@code <meta>} attributes that say what its {@code content} means: {@code name}, a pragma
   * ({@code http-equiv}), and {@code property} (Open Graph, App Links) and {@code itemprop}
   * (microdata), which crawlers read whatever the {@code name} says.
   */
  private static final Set<String> META_KEYS = Backports.setOf("name", "http-equiv", "property", "itemprop");

  /**
   * Whether the {@code content} of {@code element} (a {@code <meta>}) is plain metadata text (#539,
   * #590): every static {@code name} is on {@link #INERT_META_NAMES the allow-list} and there is at
   * least one, and no other {@link #META_KEYS key} is static, computed or {@link HtmlParser#attrMaySet
   * may be set} by a {@code t:attr*}. Any other content (a policy such as {@code referrer} or
   * {@code viewport}, an unknown name, Open Graph) takes literals only. Names and values fold A-Z only,
   * as in a browser.
   */
  private static boolean isNamedMetaContent(Element element) {
    boolean named = false;
    for (Attribute a : element.attributes()) {
      if (a.isTessera()) {
        for (String key : META_KEYS) {
          if (HtmlParser.asciiLower(a.localName()).equals(key) || HtmlParser.attrMaySet(a, key)) {
            return false; // a computed key may replace or override the static name
          }
        }
        continue;
      }
      String n = HtmlParser.asciiLower(a.name());
      if (n.equals("name")) {
        String v = a.value() == null ? "" : HtmlParser.asciiLower(HtmlParser.stripAnySpace(a.value()));
        if (!INERT_META_NAMES.contains(v)) {
          return false;
        }
        named = true;
      } else if (META_KEYS.contains(n)) {
        return false;
      }
    }
    return named;
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

  /**
   * The attributes of an SVG animation element that hold the values it sets. On {@code <animate>}
   * and {@code <set>} they are TRUSTED_URL (#539), not URL: what they hold depends on the
   * {@code attributeName} ({@code values} is a {@code ;}-separated list, and an animated
   * {@code href} can be set to {@code javascript:}). STYLE when {@link #animatesSvgCssUrl the
   * animation sets CSS}, which is why a computed {@code attributeName} is FORBIDDEN.
   */
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
    String type = HtmlParser.asciiEqualsIgnoreCase(element.name(), "script") ? staticType(element) : null;
    return type != null && JSON_SCRIPT_TYPES.contains(type);
  }

  private static OutputContext scriptContext(Element script) {
    for (Attribute a : script.attributes()) {
      if (a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.localName(), "type")) {
        return OutputContext.FORBIDDEN;
      }
    }
    String type = staticType(script);
    return type == null || SCRIPT_TYPES.contains(type) ? OutputContext.SCRIPT : OutputContext.FORBIDDEN;
  }

  /** The last static {@code type} of {@code script}, trimmed and A-Z folded, or null if it has none. */
  private static String staticType(Element script) {
    String type = null;
    for (Attribute a : script.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "type")) {
        type = a.value() == null ? "" : a.value();
      }
    }
    return type == null ? null : HtmlParser.asciiLower(HtmlParser.stripHtmlWhitespace(type));
  }
}
