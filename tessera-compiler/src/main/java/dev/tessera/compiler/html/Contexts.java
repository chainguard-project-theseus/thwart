package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The escaping-context table (docs/context-model.md §6.1). Pure functions over element and
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
   * The attributes that hold URLs but are not exactly one URL (#414, #539, #567): {@code ping} is
   * space-separated URLs, {@code srcset} and {@code imagesrcset} comma-separated image candidates.
   * TRUSTED_URL.
   */
  private static final Set<String> TRUSTED_URL_ATTRIBUTES = Backports.setOf("ping", "srcset", "imagesrcset");

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
   * {@code element} (#330, #584; the table is docs/context-model.md section 6.1). In order: names
   * that are not {@code [A-Za-z_][A-Za-z0-9_.:-]*} FORBIDDEN; {@code on*} EVENT_HANDLER;
   * {@code style} and the {@link #isSvgCssUrl SVG attributes that take a CSS url()} (also
   * {@link #animatesSvgCssUrl animated}) STYLE; a computed
   * {@link #isLegacyHtmxConnect hx-ws or hx-sse} FORBIDDEN (#562); a
   * refresh {@code content} REFRESH ({@link OutputContext#REFRESH}); {@code srcdoc}, the attributes
   * that decide how another value is read and {@link #isTreeBuilderInput tree-builder inputs}
   * FORBIDDEN; {@link #isResourceUrl resource URLs} RESOURCE_URL; {@link #TRUSTED_URL_ATTRIBUTES URL
   * lists} and {@link #ANIMATION_VALUES animation values} TRUSTED_URL; {@link #URL_ATTRIBUTES} URL;
   * other standard names that are not inert ({@code inert-attributes.txt}) and a {@code content}
   * of a {@code <meta>} that does not {@link #isNamedMetaContent name plain metadata}
   * TYPED_ATTRIBUTE; the other {@link KnownAttributes#isStandard standard names} ATTRIBUTE; and
   * every other name NEUTRAL (#583 rule 3): {@code data-*}, {@code hx-*}, {@code x-*},
   * {@code v-*}, {@code :x}, {@code @x}, a custom element's own attributes, any name outside HTML.
   */
  public static OutputContext attribute(Element element, String attributeName) {
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
    if (isLegacyHtmxConnect(attr)) {
      return OutputContext.FORBIDDEN;
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
    if (TRUSTED_URL_ATTRIBUTES.contains(attr) || (isAnimation(el) && ANIMATION_VALUES.contains(attr))) {
      return OutputContext.TRUSTED_URL;
    }
    if (URL_ATTRIBUTES.contains(attr)) {
      return OutputContext.URL;
    }
    if (KnownAttributes.needsSafeValue(element, attr)
        || (el.equals("meta") && attr.equals("content") && !isNamedMetaContent(element))) {
      return OutputContext.TYPED_ATTRIBUTE;
    }
    return KnownAttributes.isStandard(attr) ? OutputContext.ATTRIBUTE : OutputContext.NEUTRAL;
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
   * The attribute that makes Vue 3 and petite-vue skip an element and its content (#549). The
   * compiler adds it to a {@code <textarea>} or {@code <title>} with computed content, whose
   * RCDATA text can't break a value's {{ with a comment. Vue 3.5.44
   * {@code packages/compiler-core/src/parser.ts} lines 234-235 enter v-pre mode on it and line 112
   * keeps {{ }} as text there ({@code tokenizer.ts} line 457 for RCDATA); petite-vue 0.4.1
   * {@code src/walk.ts} lines 22-24 return before walking the element.
   */
  public static final String VUE_SKIP = "v-pre";

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

  /** ASCII valid custom element names (HTML 4.13.3); a non-ASCII character fails closed. */
  private static final Pattern CUSTOM_ELEMENT_NAME = Pattern.compile("[a-z][a-z0-9._]*-[a-z0-9._-]*");

  /** The names HTML reserves from custom elements (HTML 4.13.3): SVG and MathML element names. */
  private static final Set<String> RESERVED_CUSTOM_ELEMENT_NAMES =
      Backports.setOf("annotation-xml", "color-profile", "font-face", "font-face-src", "font-face-uri",
          "font-face-format", "font-face-name", "missing-glyph");

  /**
   * Whether {@code value}, a static {@code is}, is a valid custom element name (HTML 4.13.3), which
   * is all the attribute may hold: the tree builder reads it to pick the element's custom element
   * definition (#272). Any other value, such as {@code is="script"}, is a build error, so no element
   * is classified as one element and rendered as another by whatever reads {@code is} later.
   */
  public static boolean isCustomElementName(String value) {
    return value != null && CUSTOM_ELEMENT_NAME.matcher(value).matches()
        && !RESERVED_CUSTOM_ELEMENT_NAMES.contains(value);
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
   * on. No value is written there: computed content is a build error (#577), and the verifier
   * rejects any hole in that raw text ({@code VALUE_IN_RAW_TEXT}, #586). The static content must
   * end where a browser ends the element: at the first {@code </name} after the start tag.
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

  /**
   * The first static {@code type} of {@code script}, the one a browser reads, trimmed and A-Z
   * folded, or null if it has none. {@code MarkupTokenizer.startTag} goes by the last {@code type}
   * when it decides whether the content is raw text. The two never disagree because HtmlParser
   * rejects a duplicate attribute, A-Z folded, so a template has at most one {@code type}
   * ({@code HtmlParserTest.duplicateScriptTypeIsAnError}).
   */
  private static String staticType(Element script) {
    for (Attribute a : script.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "type")) {
        return HtmlParser.asciiLower(HtmlParser.stripHtmlWhitespace(a.value() == null ? "" : a.value()));
      }
    }
    return null;
  }
}
