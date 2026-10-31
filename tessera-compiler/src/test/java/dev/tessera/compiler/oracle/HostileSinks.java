package dev.tessera.compiler.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.StringLiteral;
import org.w3c.dom.Element;

/**
 * The sink table of the hostile-value sweep (#543), written from the HTML, SVG, CSS, URL and
 * framework specs, independent of {@code Contexts}, so it checks the compiler instead of restating
 * it. {@link #classify} names each sink a hostile value reached through one parsed attribute; each
 * finding is {@code {kind, detail}}. A kind starting {@code KNOWN_} is a documented limitation, not
 * reported.
 */
final class HostileSinks {

  private HostileSinks() {}

  static final String SVG = "http://www.w3.org/2000/svg";

  /** HTML/SVG/MathML attributes whose value is one URL (HTML indices "Valid URL", SVG href). */
  static final Set<String> URL = Set.of("href", "src", "action", "formaction", "cite", "poster", "background",
      "longdesc", "manifest", "xlink:href");
  /** Obsolete plugin URLs: only on object/applet/embed (no current engine fetches them; kept as low). */
  static final Set<String> PLUGIN_URL = Set.of("codebase", "classid", "archive");
  /** Space-separated URL lists that a browser fetches (ping); itemtype/itemid are never fetched. */
  static final Set<String> URL_WS = Set.of("ping");
  static final Set<String> SRCSET = Set.of("srcset", "imagesrcset");
  /** Element/attribute pairs that load code or set the document base: also no cross-origin, no data:. */
  static final Set<String> CODE = Set.of("script/src", "script/href", "script/xlink:href", "link/href", "base/href",
      "object/data", "object/codebase", "embed/src", "iframe/src", "frame/src", "object/archive", "object/classid");
  /** htmx request URLs (also with data-): htmx swaps the response into the page as HTML, so code. */
  static final Set<String> HTMX_URL = Set.of("hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete", "sse-connect",
      "ws-connect");
  /** htmx switches whose value turns on fetching and swapping (boosted links, htmx 1.x sockets). */
  static final Set<String> HTMX_SWITCH = Set.of("hx-boost", "hx-sse", "hx-ws");
  /** CSS properties whose grammar takes a url (paint, clip-path, mask, filter, marker, cursor). */
  static final Set<String> CSS_URL_PROPS = Set.of("fill", "stroke", "clip-path", "mask", "filter", "marker-start",
      "marker-mid", "marker-end", "marker", "cursor");
  static final Set<String> ANIMATIONS = Set.of("animate", "set", "animatemotion", "animatetransform", "animatecolor");
  static final Set<String> ANIM_VALUES = Set.of("values", "to", "from", "by");
  static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");
  static final Pattern CSS_FETCH = Pattern.compile(
      "(url|image|image-set|-webkit-image-set|cross-fade|element|expression|src)\\s*\\(|@import|-moz-binding|behavior\\s*:");

  static List<String[]> classify(Element el, String attrName, String value, String hostile) {
    List<String[]> out = new ArrayList<>();
    String ns = el.getNamespaceURI();
    String tag = el.getLocalName().toLowerCase(Locale.ROOT);
    String a = attrName.toLowerCase(Locale.ROOT);
    boolean svg = SVG.equals(ns);
    boolean animation = svg && ANIMATIONS.contains(tag);
    String animTarget = null;
    if (animation) {
      String an = el.getAttribute("attributeName");
      if (an == null || an.isEmpty()) {
        an = el.getAttribute("attributename");
      }
      animTarget = an == null ? "" : an.strip().toLowerCase(Locale.ROOT);
    }
    String unprefixed = a.startsWith("data-") ? a.substring(5) : a;
    // 1. URL sinks.
    String urlRole = null;
    if (URL.contains(a) || HTMX_URL.contains(unprefixed) || (tag.equals("object") && a.equals("data"))
        || (PLUGIN_URL.contains(a) && Set.of("object", "applet", "embed").contains(tag))) {
      urlRole = "one";
    }
    if (animation && ANIM_VALUES.contains(a) && (animTarget.equals("href") || animTarget.equals("xlink:href"))) {
      urlRole = a.equals("values") ? "semicolon" : "one";
    }
    List<String> items = new ArrayList<>();
    if (urlRole != null) {
      if (urlRole.equals("semicolon")) {
        items.addAll(List.of(value.split(";", -1)));
      } else {
        items.add(value);
      }
    } else if (URL_WS.contains(a)) {
      items.addAll(List.of(value.strip().split("[\\t\\n\\f\\r ]+")));
    } else if (SRCSET.contains(a)) {
      for (String cand : value.split(",")) {
        String s = cand.strip();
        if (!s.isEmpty()) {
          items.add(s.split("[\\t\\n\\f\\r ]+")[0]);
        }
      }
    } else if (tag.equals("meta") && a.equals("content") && "refresh".equalsIgnoreCase(el.getAttribute("http-equiv").strip())) {
      Matcher m = Pattern.compile("(?i)^\\s*[0-9.]*\\s*[;,]?\\s*(url\\s*=\\s*)?['\"]?([^'\"]*)").matcher(value);
      if (m.find() && !m.group(2).isEmpty()) {
        items.add(m.group(2));
        if (!m.group(2).startsWith("/") || m.group(2).startsWith("//")) {
          out.add(new String[] {"URL_REFRESH_OFFSITE", "refresh to " + m.group(2)});
        }
      }
    }
    boolean code = CODE.contains(tag + "/" + a) || HTMX_URL.contains(unprefixed)
        || (svg && tag.equals("use") && (a.endsWith("href")));
    for (String item : items) {
      String u = item.replaceAll("^[\\u0000-\\u0020]+|[\\u0000-\\u0020]+$", "").replaceAll("[\\t\\n\\r]", "");
      Matcher m = SCHEME.matcher(u);
      String scheme = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
      if (scheme.equals("javascript") || scheme.equals("vbscript")) {
        out.add(new String[] {"URL_SCRIPT_SCHEME", "item '" + item + "'"});
      } else if (scheme.equals("data") && (code || animation)) {
        out.add(new String[] {"URL_DATA_RESOURCE", "item '" + item + "'"});
      } else if (code && (u.startsWith("//") || u.startsWith("\\\\") || u.startsWith("/\\") || u.startsWith("\\/")
          || scheme.startsWith("http"))) {
        out.add(new String[] {"URL_CROSS_ORIGIN_CODE", "item '" + item + "'"});
      }
    }
    if (items.size() > 1 && !"semicolon".equals(urlRole)) {
      out.add(new String[] {"URL_LIST_CONTROLLED", items.size() + " items from one value"});
    }
    // 2. CSS sinks.
    boolean css = a.equals("style")
        || (svg && CSS_URL_PROPS.contains(a) && !(a.equals("fill") && animation))
        || (animation && ANIM_VALUES.contains(a) && (CSS_URL_PROPS.contains(animTarget) || animTarget.equals("style")));
    if (css && CSS_FETCH.matcher(cssNormalize(value)).find()) {
      out.add(new String[] {"CSS_FETCH", "css '" + value + "'"});
    }
    // 3. Tree-builder and policy inputs: the parser or the browser reads them before any page code.
    boolean tb = a.equals("is") || a.equals("xmlns") || a.startsWith("xmlns:")
        || (tag.equals("meta") && (a.equals("charset") || a.equals("http-equiv")
            || (a.equals("content") && "content-type".equalsIgnoreCase(el.getAttribute("http-equiv").strip()))))
        || (tag.equals("template") && (a.startsWith("shadowroot") || a.equals("for")))
        || (tag.equals("annotation-xml") && a.equals("encoding"))
        || (tag.equals("script") && a.equals("type"))
        || (animation && a.equals("attributename"));
    if (tb && value.equals(hostile)) {
      out.add(new String[] {"TREE_BUILDER_INPUT", "browser reads '" + value + "'"});
    }
    boolean policy = (tag.equals("iframe") && Set.of("sandbox", "allow", "csp", "allowfullscreen").contains(a))
        || (tag.equals("link") && Set.of("rel", "as", "integrity", "crossorigin").contains(a))
        || (tag.equals("script") && Set.of("integrity", "crossorigin", "nomodule", "nonce").contains(a))
        || HTMX_SWITCH.contains(unprefixed);
    if (policy && value.equals(hostile)) {
      out.add(new String[] {"POLICY_INPUT", "policy attribute holds '" + value + "'"});
    }
    // 4. Markup: srcdoc is parsed as a whole HTML document.
    if (a.equals("srcdoc") && !value.isEmpty()) {
      out.add(new String[] {"MARKUP_ATTRIBUTE", "srcdoc holds '" + value + "'"});
    }
    // 5. Frameworks.
    if (a.startsWith("on") && !value.isEmpty()) {
      out.add(new String[] {"EVENT_HANDLER", "on* holds '" + value + "'"});
    }
    String ng = angularNormalize(attrName);
    if (ng.matches("ng([A-Z].*)?") && value.equals(hostile)) {
      out.add(new String[] {"ANGULAR_DIRECTIVE", attrName + " normalizes to " + ng});
    }
    if (a.equals("class") || (animation && ANIM_VALUES.contains(a) && animTarget.equals("class"))) {
      for (String d : angularClassDirectives(value)) {
        out.add(new String[] {"ANGULAR_CLASS_DIRECTIVE", "class token normalizes to " + d});
      }
    }
    if (value.contains("{{")) {
      // Attributes have no lossless {{ break; Vue reads none, and AngularJS, which interpolates
      // them, is not supported (guide section 7). Not reported, in templates or sanitizer output.
      out.add(new String[] {"KNOWN_LIMITATION_CSTI", "value holds {{"});
    }
    // htmx getValuesForElement evaluates these after a js: or javascript: prefix.
    String lowerValue = value.strip().toLowerCase(Locale.ROOT);
    if ((unprefixed.equals("hx-vals") || unprefixed.equals("hx-headers") || unprefixed.equals("hx-request"))
        && (lowerValue.startsWith("js:") || lowerValue.startsWith("javascript:"))
        || unprefixed.equals("hx-vars") && !value.isEmpty()) {
      out.add(new String[] {"HTMX_EVAL", "htmx evaluates '" + value + "'"});
    }
    // Names a framework runs as code: htmx hx-on* and hx-trigger (its [filter] is JS), Alpine x-*,
    // Vue v-*, @x, #x, .x, Knockout data-bind and params on a custom element, _hyperscript.
    if ((unprefixed.startsWith("hx-on") || unprefixed.equals("hx-trigger")
        || a.startsWith("x-") && !a.startsWith("x-ng") && !angularIsDirective(ng)
        || a.startsWith("v-") || a.startsWith("@") || a.startsWith("#") || a.startsWith(".")
        || a.equals("data-bind") || (a.equals("params") && tag.contains("-"))
        || a.equals("_") || a.equals("script") || a.equals("data-script"))
        && value.equals(hostile)) {
      out.add(new String[] {"FRAMEWORK_EXPRESSION", attrName + " holds an expression"});
    }
    // Vue/Alpine bindings: the value is a JS expression whose result lands in the target.
    for (String bp : List.of("v-bind:", "x-bind:", ":")) {
      if (a.startsWith(bp)) {
        String targetName = a.substring(bp.length());
        String result = jsStringValue(value);
        if (result != null && !result.equals(value)) {
          for (String[] f : classify(el, targetName, result, hostile)) {
            // Guide section 7: AngularJS is not supported, and neither {{ in attributes nor the
            // classes a Vue or Alpine :class binding writes are covered for it.
            boolean known = f[0].startsWith("KNOWN_") || f[0].equals("ANGULAR_CLASS_DIRECTIVE");
            out.add(new String[] {(known ? "KNOWN_BINDING_" : "BINDING_") + f[0].replaceFirst("^KNOWN_", ""),
                bp + targetName + " evaluates to '" + result + "': " + f[1]});
          }
        } else if (value.equals(hostile)) {
          out.add(new String[] {"FRAMEWORK_EXPRESSION", "binding holds raw expression"});
        }
        if (Set.of("innerhtml", "outerhtml", "srcdoc", "inner-h-t-m-l", "outer-h-t-m-l").contains(targetName)
            && result != null && !result.isEmpty()) {
          out.add(new String[] {"BINDING_MARKUP_PROP", bp + targetName});
        }
        break;
      }
    }
    return out;
  }

  static boolean angularIsDirective(String ng) {
    return ng.matches("ng([A-Z].*)?");
  }

  /** CSS: drop comments, decode escapes, lower-case. */
  static String cssNormalize(String v) {
    String s = v.replaceAll("/\\*.*?\\*/", "");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        int j = i + 1;
        while (j < s.length() && j < i + 7 && Character.digit(s.charAt(j), 16) >= 0) {
          j++;
        }
        if (j > i + 1) {
          sb.appendCodePoint(Integer.parseInt(s.substring(i + 1, j), 16));
          if (j < s.length() && Character.isWhitespace(s.charAt(j))) {
            j++;
          }
          i = j - 1;
        } else {
          sb.append(s.charAt(i + 1));
          i++;
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /** AngularJS 1.8.3 directiveNormalize, ported from src/ng/compile.js (lower-cases first, as $compile does for attrs). */
  static String angularNormalize(String name) {
    String n = name.toLowerCase(Locale.ROOT).replaceFirst("^((?:x|data)[:\\-_])", "");
    Matcher m = Pattern.compile("[:\\-_]+(.)").matcher(n);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(m.start() == 0 ? m.group(1) : m.group(1).toUpperCase(Locale.ROOT)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** AngularJS class directives: CLASS_DIRECTIVE_REGEXP = /(([\w-]+)(?::([^;]+))?;?)/ over className. */
  static List<String> angularClassDirectives(String className) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("(([A-Za-z0-9_\\-]+)(?::([^;]+))?;?)").matcher(className);
    while (m.find()) {
      String d = classNormalize(m.group(2));
      // With or without a value: a directive token with none still runs the directive (#531).
      if (d.matches("ng([A-Z].*)?")) {
        out.add(m.group(3) == null ? d : d + ":" + m.group(3));
      }
    }
    return out;
  }

  /** Class names are not lower-cased by $compile. */
  static String classNormalize(String name) {
    String n = name.replaceFirst("(?i)^((?:x|data)[:\\-_])", "");
    Matcher m = Pattern.compile("[:\\-_]+(.)").matcher(n);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(m.start() == 0 ? m.group(1) : m.group(1).toUpperCase(Locale.ROOT)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** The value of a JS string literal expression (JSON-ish), else null. */
  static String jsStringValue(String expr) {
    String e = expr.strip();
    if (e.length() < 2 || !(e.startsWith("\"") && e.endsWith("\"") || e.startsWith("'") && e.endsWith("'"))) {
      return null;
    }
    try {
      CompilerEnvirons env = HostileSweepTest.env();
      AstRoot root = new Parser(env, env.getErrorReporter()).parse("(" + e + ")", "b", 1);
      AstNode first = (AstNode) root.getFirstChild();
      AstNode[] found = new AstNode[1];
      first.visit(n -> {
        if (found[0] == null && n instanceof StringLiteral) {
          found[0] = n;
        }
        return true;
      });
      return found[0] == null ? null : ((StringLiteral) found[0]).getValue();
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
