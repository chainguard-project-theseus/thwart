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
   * The attributes whose grammar is exactly one URL: WHATWG "valid (non-empty) URL
   * potentially surrounded by spaces" ({@code href src action formaction cite poster}), the
   * obsolete {@code background longdesc manifest}, and {@code xlink:href} (no HTML element defines
   * it; it keeps the stricter URL check rather than a plain value). Only these take the URL
   * escaper, which checks the whole value as one URL.
   */
  private static final Set<String> URL_ATTRIBUTES =
      Backports.setOf(
          "href", "src", "action", "formaction", "cite", "poster", "background", "longdesc", "manifest",
          "xlink:href");

  /**
   * The attributes that hold URLs but are not exactly one URL: {@code ping} is
   * space-separated URLs, {@code srcset} and {@code imagesrcset} comma-separated image candidates.
   * A computed one is FORBIDDEN: {@link #forbidden}.
   */
  private static final Set<String> URL_LIST_ATTRIBUTES = Backports.setOf("ping", "srcset", "imagesrcset");

  /**
   * Why nothing inside {@code <svg>} or {@code <math>} is computed, with the fix-it, naming
   * {@code root} when it is one of them (null or another name: both).
   */
  public static String foreign(String root) {
    String n = root == null ? "" : HtmlParser.asciiLower(root);
    String r = n.equals("svg") || n.equals("math") ? "<" + n + ">" : "<svg> or <math>";
    return "a browser parses SVG and MathML by rules Tessera doesn't check, so nothing inside " + r + " is computed;"
        + " write it as static markup and set what changes outside it: a t:class or t:style on an HTML parent with"
        + " CSS (fill: currentColor, a CSS variable), or one static " + r + " per case under t:if or t:switch";
  }

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
   * {@code element} (the table is docs/context-model.md section 6.1). In order: what
   * {@link #forbidden} gives a reason for FORBIDDEN; {@code on*} EVENT_HANDLER; {@code style} STYLE;
   * a refresh {@code content} REFRESH ({@link OutputContext#REFRESH}); {@link #isResourceUrl
   * resource URLs} RESOURCE_URL; {@link #URL_ATTRIBUTES} URL; other standard names that are not
   * inert ({@code inert-attributes.txt}) and a {@code content} of a {@code <meta>} that does not
   * {@link #isNamedMetaContent name plain metadata} TYPED_ATTRIBUTE; the other
   * {@link KnownAttributes#isStandard standard names} ATTRIBUTE; and every other plain name NEUTRAL
   * (client frameworks may run any non-standard attribute as code): {@code data-*}, {@code hx-*}, {@code x-*}, {@code v-*}, a custom element's own
   * attributes, any other name outside HTML. {@code :x} and {@code @x} are not plain names, so they
   * are FORBIDDEN.
   */
  public static OutputContext attribute(Element element, String attributeName) {
    if (forbidden(element, attributeName) != null) {
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

  /**
   * Why a computed attribute {@code attributeName} on {@code element} is FORBIDDEN, with the fix-it,
   * or null if it is not: every attribute of an SVG or MathML element; a name that is not
   * {@code [A-Za-z_][A-Za-z0-9_.:-]*}; {@code srcdoc}; a {@link #URL_LIST_ATTRIBUTES URL list};
   * and a {@link #treeBuilderInput tree-builder input}. The only place that decides
   * an attribute is FORBIDDEN.
   */
  public static String forbidden(Element element, String attributeName) {
    if (element.foreign()) {
      return foreign(null);
    }
    if (attributeName == null || !PLAIN_NAME.matcher(attributeName).matches()) {
      return "attribute name '" + attributeName + "' is not allowed; names must match [A-Za-z_][A-Za-z0-9_.:-]*";
    }
    String attr = HtmlParser.asciiLower(attributeName);
    if (attr.equals("srcdoc")) {
      return "srcdoc is a whole HTML document and cannot contain expressions; use src with a URL";
    }
    if (URL_LIST_ATTRIBUTES.contains(attr)) {
      return "it holds a list of URLs, which Tessera doesn't check; write it as a static value, or compute a single"
          + " src or href instead";
    }
    return treeBuilderInput(element, attributeName, attr);
  }

  /**
   * Why static attribute {@code name="value"} on {@code element} is rejected, with the fix-it, or
   * null if it is not: an {@code on*} event handler, or a {@code javascript:} URL in a URL attribute
   * (one {@link #URL_ATTRIBUTES} or {@link #isResourceUrl resource URL} names, on any element). Both
   * are inline script, which the strict nonce CSP blocks, so a template never depends on one. The
   * scheme is found as {@code Escapers.isAllowedUrl} finds it: {@code value} has its character
   * references decoded, leading C0 controls and spaces are skipped and tab/CR/LF ignored.
   */
  public static String staticScript(Element element, String name, String value) {
    String attr = HtmlParser.asciiLower(name);
    if (attr.startsWith("on")) {
      return "inline event handlers are not supported: a strict CSP blocks them; remove " + name + " and attach"
          + " the listener from a <script> with addEventListener, or mark the element with a data-* attribute and"
          + " handle it with one delegated listener";
    }
    if (value == null || !(URL_ATTRIBUTES.contains(attr) || isResourceUrl(HtmlParser.asciiLower(element.name()), attr))) {
      return null;
    }
    StringBuilder scheme = new StringBuilder();
    for (int i = 0; i < value.length() && scheme.length() < 11; i++) {
      char c = value.charAt(i);
      if (c != '\t' && c != '\n' && c != '\r' && (c > ' ' || scheme.length() > 0)) {
        scheme.append(c);
      }
    }
    return !HtmlParser.asciiLower(scheme.toString()).equals("javascript:") ? null
        : "javascript: URLs are not supported: a strict CSP blocks them; use href=\"#\" or a <button type=\"button\">"
            + " and attach the listener from a <script> with addEventListener";
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
   * acts on: a computed content is plain ATTRIBUTE only under one of these.
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
   * Whether the {@code content} of {@code element} (a {@code <meta>}) is plain metadata text:
   * every static {@code name} is on {@link #INERT_META_NAMES the allow-list} and there is at
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
   * definition. Any other value, such as {@code is="script"}, is a build error, so no element
   * is classified as one element and rendered as another by whatever reads {@code is} later.
   */
  public static boolean isCustomElementName(String value) {
    return value != null && CUSTOM_ELEMENT_NAME.matcher(value).matches()
        && !RESERVED_CUSTOM_ELEMENT_NAMES.contains(value);
  }

  /**
   * Why a computed {@code name} ({@code attr} A-Z folded) on {@code element} is FORBIDDEN because
   * the HTML tree builder reads it while it builds the page (HTML 13.2.6), or it decides how other
   * content is read, or null if it is neither: {@code is} (which custom element to create),
   * {@code xmlns*} (checked against the namespace) and {@code encoding} (whether a MathML
   * {@code annotation-xml} holds HTML) on any element; {@code type} on {@code <input>} (a hidden
   * input stays in a table) and {@code <script>} (whether its content is JavaScript); {@code color},
   * {@code face} and {@code size} on {@code <font>} (they end foreign content); a
   * {@code <template>}'s {@code shadowroot*} attributes (a declarative shadow root) and {@code for};
   * a {@code <meta>}'s {@code charset} and {@code http-equiv}, and its {@code content} when its
   * static {@code http-equiv} is {@code Content-Type} (the encoding the page is read in).
   */
  private static String treeBuilderInput(Element element, String name, String attr) {
    String computed = "a computed '" + name + "' ";
    if (attr.equals("is")) {
      return computed + "decides which custom element a browser creates; write it as a static value";
    }
    if (attr.startsWith("xmlns")) {
      return computed + "must name the element's namespace, which a browser checks while it parses; write it"
          + " as a static value, or omit it";
    }
    if (attr.equals("encoding")) {
      return computed + "decides whether a browser parses MathML <annotation-xml> content as HTML; write it as a"
          + " static value";
    }
    switch (HtmlParser.asciiLower(element.name())) {
      case "input":
        return !attr.equals("type") ? null
            : "the type of an <input> cannot be computed; a browser reads it while it builds the page,"
                + " so write a static type attribute, or one <input> per type under t:if";
      case "script":
        return !attr.equals("type") ? null
            : "the type of a <script> cannot be computed; its content type must be known at"
                + " build time, so write a static type attribute";
      case "font":
        return !(attr.equals("color") || attr.equals("face") || attr.equals("size")) ? null
            : computed + "on a <font> decides whether a browser ends <svg> or <math> content there; use a <span>"
                + " with t:class or t:style instead";
      case "template":
        return !(attr.startsWith("shadowroot") || attr.equals("for")) ? null
            : computed + "on a <template> decides whether a browser attaches its content as a shadow root, or"
                + " where it moves it; write it as a static value";
      case "meta":
        if (attr.equals("http-equiv")) {
          return "the http-equiv of a <meta> cannot be computed; it decides whether content is"
              + " a refresh URL, so write a static http-equiv attribute";
        }
        if (attr.equals("charset")) {
          return computed + "on a <meta> sets the encoding a browser reads the page in; write it as a static value,"
              + " such as <meta charset=\"utf-8\">";
        }
        return !(attr.equals("content") && hasHttpEquiv(element, "content-type")) ? null
            : computed + "on a <meta http-equiv=\"Content-Type\"> sets the encoding a browser reads the page in;"
                + " write it as a static value, or set the encoding in the Content-Type response header";
      default:
        return null;
    }
  }

  private static boolean isResourceUrl(String element, String attr) {
    switch (element) {
      case "script": // src loads code; href and xlink:href get the same stricter check.
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
   * on. No value is written there: computed content is a build error, and the verifier
   * rejects any hole in that raw text ({@code VALUE_IN_RAW_TEXT}). The static content must
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
