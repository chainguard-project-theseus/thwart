package dev.chainguard.thwart.compiler.html;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.model.OutputContext;
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
      Backports.setOf("iframe", "noscript");

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
    return "a browser parses SVG and MathML by rules Thwart doesn't check, so nothing inside " + r + " is computed;"
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
   * The closed allow-list of computed attribute names (#761), besides {@code data-*}, {@code aria-*},
   * {@code <object data>} and the {@code content} of a {@code <meta http-equiv="refresh">}: the names
   * the migration corpus computes (#760) and their obvious form, table and accessibility siblings.
   * Each has one fixed context ({@link #attribute}). Every other computed name is a build error
   * ({@link #forbidden}); static attributes are not checked against it.
   */
  private static final Set<String> COMPUTED =
      Backports.setOf(
          // URL, or RESOURCE_URL where isResourceUrl says so; STYLE; TYPED_ATTRIBUTE
          "href", "src", "action", "formaction", "cite", "poster", "style", "target",
          // ATTRIBUTE. Text:
          "abbr", "alt", "download", "id", "label", "name", "pattern", "placeholder", "title", "value",
          // keywords:
          "autocomplete", "dir", "enterkeyhint", "formmethod", "hidden", "inputmode", "method", "scope", "spellcheck",
          "translate",
          // numbers, dates and times:
          "cols", "colspan", "datetime", "height", "high", "low", "max", "maxlength", "min", "minlength", "optimum",
          "rows", "rowspan", "size", "span", "start", "step", "tabindex", "width",
          // booleans:
          "async", "autofocus", "checked", "defer", "disabled", "formnovalidate", "multiple", "novalidate", "open",
          "readonly", "required", "reversed", "selected",
          // ID references, tokens and language tags:
          "for", "form", "headers", "list", "accept", "class", "hreflang", "lang",
          // ARIA (WAI-ARIA 1.2): role and every state and property; another aria-* name is NEUTRAL.
          "role", "aria-activedescendant", "aria-atomic", "aria-autocomplete", "aria-busy", "aria-checked",
          "aria-colcount", "aria-colindex", "aria-colspan", "aria-controls", "aria-current", "aria-describedby",
          "aria-details", "aria-disabled", "aria-dropeffect", "aria-errormessage", "aria-expanded", "aria-flowto",
          "aria-grabbed", "aria-haspopup", "aria-hidden", "aria-invalid", "aria-keyshortcuts", "aria-label",
          "aria-labelledby", "aria-level", "aria-live", "aria-modal", "aria-multiline", "aria-multiselectable",
          "aria-orientation", "aria-owns", "aria-placeholder", "aria-posinset", "aria-pressed", "aria-readonly",
          "aria-relevant", "aria-required", "aria-roledescription", "aria-rowcount", "aria-rowindex", "aria-rowspan",
          "aria-selected", "aria-setsize", "aria-sort", "aria-valuemax", "aria-valuemin", "aria-valuenow",
          "aria-valuetext");

  /** The elements a computed {@code value} is plain text on; elsewhere ({@code <param>}) TYPED_ATTRIBUTE. */
  private static final Set<String> VALUE_TEXT_ELEMENTS =
      Backports.setOf("button", "data", "input", "li", "meter", "option", "progress", "select");

  /**
   * Context for the value of attribute {@code attributeName} (without {@code t:}) on
   * {@code element} (the table is docs/context-model.md section 6.1). In order: what
   * {@link #forbidden} gives a reason for (every name off the {@link #COMPUTED allow-list} among
   * them) FORBIDDEN; {@code on*} EVENT_HANDLER; {@code style} STYLE; a refresh {@code content}
   * REFRESH; {@link #isResourceUrl resource URLs} RESOURCE_URL; {@link #URL_ATTRIBUTES} URL;
   * {@code target}, a {@code value} off {@link #VALUE_TEXT_ELEMENTS} and a {@code for} off
   * {@code <label>} and {@code <output>} TYPED_ATTRIBUTE; {@code data-*} and an {@code aria-*} name
   * outside WAI-ARIA 1.2 NEUTRAL (client frameworks may run them as code); every other listed name
   * ATTRIBUTE.
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
    if (attr.equals("target")
        || (attr.equals("value") && !VALUE_TEXT_ELEMENTS.contains(el))
        || (attr.equals("for") && !el.equals("label") && !el.equals("output"))) {
      return OutputContext.TYPED_ATTRIBUTE;
    }
    return COMPUTED.contains(attr) ? OutputContext.ATTRIBUTE : OutputContext.NEUTRAL;
  }

  /**
   * Whether a computed {@code attr} (A-Z folded) on {@code element} is on the closed allow-list:
   * {@link #COMPUTED}, {@code data-*}, {@code aria-*}, {@code <object data>} and the {@code content}
   * of a {@code <meta http-equiv="refresh">}. {@code on*} names are left to EVENT_HANDLER, which
   * rejects them with their own fix-it.
   */
  private static boolean allowed(Element element, String attr) {
    String el = HtmlParser.asciiLower(element.name());
    return COMPUTED.contains(attr) || attr.startsWith("data-") || attr.startsWith("aria-") || attr.startsWith("on")
        || (el.equals("object") && attr.equals("data")) || isRefreshContent(element, el, attr);
  }

  /** Why a computed {@code name} ({@code attr} A-Z folded) off the allow-list is rejected, with the fix-it. */
  private static String offList(String name, String attr) {
    if (attr.equals("nonce")) {
      return "Thwart stamps the CSP nonce itself; delete this attribute";
    }
    return "'" + name + "' is not on Thwart's list of computed attributes (docs/guide.md section 4.4); write it as"
        + " a static value, or one element per value under t:if; for data your script reads, put a number, boolean or"
        + " link in t:data-" + attr + ", and any other value in a script declaration such as var x = [[${x}]]";
  }

  /**
   * Why a computed attribute {@code attributeName} on {@code element} is FORBIDDEN, with the fix-it,
   * or null if it is not: every attribute of an SVG or MathML element; a name that is not
   * {@code [A-Za-z_][A-Za-z0-9_.:-]*}; {@code srcdoc}; a {@link #URL_LIST_ATTRIBUTES URL list};
   * a {@link #treeBuilderInput tree-builder input}; and a name off the {@link #allowed allow-list}.
   * The only place that decides an attribute is FORBIDDEN.
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
      return "it holds a list of URLs, which Thwart doesn't check; write it as a static value, or compute a single"
          + " src or href instead";
    }
    String tree = treeBuilderInput(element, attributeName, attr);
    return tree != null || allowed(element, attr) ? tree : offList(attributeName, attr);
  }

  /**
   * Why static attribute {@code name="value"} on {@code element} is rejected, with the fix-it, or
   * null if it is not: an {@code on*} event handler, or a {@code javascript:} URL in a URL attribute
   * (one {@link #URL_ATTRIBUTES} or {@link #isResourceUrl resource URL} names, on any element). Both
   * are inline script, which the strict nonce CSP blocks, so a template never depends on one. The
   * scheme is found as {@code Escapers.isAllowedUrl} finds it: leading C0 controls and spaces are
   * skipped and tab/CR/LF ignored. The parser decodes numeric and a few named references; a browser
   * decodes every named one ({@code &colon;}, {@code &Tab;}, {@code &NewLine;}), and none of them is
   * an ASCII letter. So the {@link #UNDECODED_REFERENCE references left} in {@code value} are
   * removed, and if one was, a value whose letters then start with {@code javascript} is rejected
   * (fail-closed: {@code java&Tab;script&colon;x} is {@code javascript:x} to a browser).
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
    String s = UNDECODED_REFERENCE.matcher(value).replaceAll("");
    StringBuilder scheme = new StringBuilder();
    for (int i = 0; i < s.length() && scheme.length() < 11; i++) {
      char c = s.charAt(i);
      if (c != '\t' && c != '\n' && c != '\r' && (c > ' ' || scheme.length() > 0)) {
        scheme.append(c);
      }
    }
    String lower = HtmlParser.asciiLower(scheme.toString());
    boolean javascript = lower.equals("javascript:") || (s.length() != value.length() && lower.startsWith("javascript"));
    return !javascript ? null
        : "javascript: URLs are not supported: a strict CSP blocks them; use href=\"#\" or a <button type=\"button\">"
            + " and attach the listener from a <script> with addEventListener";
  }

  /**
   * A named character reference the parser did not decode: a browser decodes it, to any character.
   * Static URL checks treat it so ({@link #staticScript}, {@code CsrfInjector.isSameOriginStatic}).
   */
  public static final Pattern UNDECODED_REFERENCE = Pattern.compile("&[A-Za-z][A-Za-z0-9]*;?");

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
      if (!a.isThwart()
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
   * Whether a browser reads the content of element {@code name} as raw text while Thwart parses
   * it as markup: {@code iframe}, and {@code noscript} when scripting is
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
      if (a.isThwart() && HtmlParser.asciiEqualsIgnoreCase(a.localName(), "type")) {
        return OutputContext.FORBIDDEN;
      }
    }
    String type = staticType(script);
    return type == null || SCRIPT_TYPES.contains(type) ? OutputContext.SCRIPT : OutputContext.FORBIDDEN;
  }

  /**
   * The first static {@code type} of {@code script}, the one a browser reads, trimmed and A-Z
   * folded, or null if it has none. This is the only script-type table: the tokenizer reads every
   * script's content as raw text, whatever its type, as a browser does.
   */
  private static String staticType(Element script) {
    for (Attribute a : script.attributes()) {
      if (!a.isThwart() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "type")) {
        return HtmlParser.asciiLower(HtmlParser.stripHtmlWhitespace(a.value() == null ? "" : a.value()));
      }
    }
    return null;
  }
}
