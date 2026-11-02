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
   * A computed one is FORBIDDEN (#678): {@link #URL_LIST}.
   */
  private static final Set<String> URL_LIST_ATTRIBUTES = Backports.setOf("ping", "srcset", "imagesrcset");

  /** Why a computed attribute of an SVG or MathML element is a build error, with the fix-it (#678). */
  public static final String FOREIGN =
      "a browser parses SVG and MathML by rules Tessera doesn't check, so nothing inside <svg> or <math> is computed;"
          + " write it as static markup and set what changes outside it: a t:class or t:style on an HTML parent with"
          + " CSS (fill: currentColor, a CSS variable), or one static <svg> per case under t:if or t:switch";

  /** Why a computed {@link #isUrlList URL list} is a build error, with the fix-it. */
  public static final String URL_LIST =
      "it holds a list of URLs, which Tessera doesn't check; write it as a static value, or compute a single"
          + " src or href instead";

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
   * {@code style} STYLE; a
   * refresh {@code content} REFRESH ({@link OutputContext#REFRESH}); {@code srcdoc},
   * {@link #isUrlList URL lists} and {@link #isTreeBuilderInput tree-builder inputs} FORBIDDEN (#678);
   * {@link #isResourceUrl resource URLs} RESOURCE_URL; {@link #URL_ATTRIBUTES} URL; other standard names
   * that are not inert ({@code inert-attributes.txt}) and a {@code content} of a {@code <meta>} that
   * does not {@link #isNamedMetaContent name plain metadata} TYPED_ATTRIBUTE; the other
   * {@link KnownAttributes#isStandard standard names} ATTRIBUTE; and every other name NEUTRAL (#583
   * rule 3): {@code data-*}, {@code hx-*}, {@code x-*}, {@code v-*}, {@code :x}, {@code @x}, a custom
   * element's own attributes, any name outside HTML. Every attribute of an SVG or MathML element is
   * FORBIDDEN ({@link #FOREIGN}, #678).
   */
  public static OutputContext attribute(Element element, String attributeName) {
    if (attributeName == null || !PLAIN_NAME.matcher(attributeName).matches() || element.foreign()) {
      return OutputContext.FORBIDDEN;
    }
    String el = HtmlParser.asciiLower(element.name());
    String attr = HtmlParser.asciiLower(attributeName);
    if (attr.startsWith("on")) {
      return OutputContext.EVENT_HANDLER;
    }
    if (attr.equals("style")) {
      return OutputContext.STYLE;
    }
    if (isRefreshContent(element, el, attr)) {
      return OutputContext.REFRESH;
    }
    if (attr.equals("srcdoc") || isUrlList(attr) || isTreeBuilderInput(element, attributeName)) {
      return OutputContext.FORBIDDEN;
    }
    if (isResourceUrl(el, attr)) {
      return OutputContext.RESOURCE_URL;
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

  /** Whether {@code attributeName} (any case) is a {@link #URL_LIST_ATTRIBUTES URL list}, FORBIDDEN computed (#678). */
  public static boolean isUrlList(String attributeName) {
    return URL_LIST_ATTRIBUTES.contains(HtmlParser.asciiLower(attributeName));
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
   * builds the page (HTML 13.2.6), or that decides how other content is read, so a computed value is
   * FORBIDDEN (#678): {@code is} (which custom element to create), {@code xmlns*} (checked against the
   * namespace) and {@code encoding} (whether a MathML {@code annotation-xml} holds HTML) on any
   * element; {@code type} on {@code <input>} (a hidden input stays in a table) and {@code <script>}
   * (whether its content is JavaScript); {@code color}, {@code face} and {@code size} on
   * {@code <font>} (they end foreign content); a {@code <template>}'s {@code shadowroot*} attributes
   * (a declarative shadow root) and {@code for}; a {@code <meta>}'s {@code charset} and
   * {@code http-equiv}, and its {@code content} when its static {@code http-equiv} is
   * {@code Content-Type} (the encoding the page is read in). Case-insensitive.
   */
  public static boolean isTreeBuilderInput(Element element, String attributeName) {
    String el = HtmlParser.asciiLower(element.name());
    String attr = HtmlParser.asciiLower(attributeName);
    if (attr.equals("is") || attr.startsWith("xmlns") || attr.equals("encoding")) {
      return true;
    }
    switch (el) {
      case "input":
      case "script":
        return attr.equals("type");
      case "font":
        return attr.equals("color") || attr.equals("face") || attr.equals("size");
      case "template":
        return attr.startsWith("shadowroot") || attr.equals("for");
      case "meta":
        return attr.equals("charset") || attr.equals("http-equiv")
            || (attr.equals("content") && hasHttpEquiv(element, "content-type"));
      default:
        return false;
    }
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
   * other script types, STYLE inside {@code <style>}, otherwise TEXT (the
   * RCDATA of a {@code <title>} or {@code <textarea>} included).
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
