package dev.chainguard.thwart.compiler.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * The sink table of the hostile-value sweep (#543), written from the HTML, CSS and URL specs,
 * independent of {@code Contexts}, so it checks the compiler instead of restating it. It names no
 * client framework (#583): for a name outside HTML it checks only the shape of the value, which
 * must be framework-neutral. {@link #classify} names each sink a hostile value reached through one
 * parsed attribute; each finding is {@code {kind, detail}}.
 */
final class HostileSinks {

  private HostileSinks() {}

  /**
   * The standard HTML, SVG, MathML and ARIA attribute names, each with the specs it comes from, as
   * the spec indices list them. Every other name is outside HTML. This is the oracle's own copy
   * ({@code oracle/standard-attributes.txt}), written from the spec indices, not from the compiler's
   * closed allow-list of computed names (#761), so the oracle does not restate the compiler (#608).
   */
  static final Map<String, Set<String>> STANDARD = standardNames("oracle/standard-attributes.txt");

  /**
   * The framework-neutral values (#583 rule 3, the grammar of the safety research there): a plain
   * decimal number, {@code true} or {@code false}, or a path that starts with exactly one {@code /}
   * and holds only {@code A-Z a-z 0-9 . _ ~ / -} and {@code %XX}; and nowhere {@code --}.
   */
  static final Pattern NEUTRAL =
      Pattern.compile("true|false|-?(0|[1-9][0-9]*)(\\.[0-9]+)?|/(?!/)([A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})*");

  static boolean neutral(String value) {
    return NEUTRAL.matcher(value).matches() && !value.contains("--");
  }

  /** A-Z to a-z and nothing else, as browsers fold attribute names. */
  static String asciiLower(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
    }
    return sb.toString();
  }

  static Map<String, Set<String>> standardNames(String resource) {
    String text;
    try (InputStream in = HostileSinks.class.getClassLoader().getResourceAsStream(resource)) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (String line : text.split("\n")) {
      if (!line.isBlank() && !line.startsWith("#")) {
        List<String> p = List.of(line.trim().split("\\s+"));
        out.put(p.get(0), Set.copyOf(p.subList(1, p.size())));
      }
    }
    return java.util.Collections.unmodifiableMap(out);
  }

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
  static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");
  static final Pattern CSS_FETCH = Pattern.compile(
      "(url|image|image-set|-webkit-image-set|cross-fade|element|expression|src)\\s*\\(|@import|-moz-binding|behavior\\s*:");

  static List<String[]> classify(Element el, String attrName, String value, String hostile) {
    List<String[]> out = new ArrayList<>();
    String tag = el.getLocalName().toLowerCase(Locale.ROOT);
    String a = attrName.toLowerCase(Locale.ROOT);
    // 1. URL sinks.
    List<String> items = new ArrayList<>();
    if (URL.contains(a) || (tag.equals("object") && a.equals("data"))
        || (PLUGIN_URL.contains(a) && Set.of("object", "applet", "embed").contains(tag))) {
      items.add(value);
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
    boolean code = CODE.contains(tag + "/" + a);
    for (String item : items) {
      String u = item.replaceAll("^[\\u0000-\\u0020]+|[\\u0000-\\u0020]+$", "").replaceAll("[\\t\\n\\r]", "");
      Matcher m = SCHEME.matcher(u);
      String scheme = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
      if (scheme.equals("javascript") || scheme.equals("vbscript")) {
        out.add(new String[] {"URL_SCRIPT_SCHEME", "item '" + item + "'"});
      } else if (scheme.equals("data") && code) {
        out.add(new String[] {"URL_DATA_RESOURCE", "item '" + item + "'"});
      } else if (code && (u.startsWith("//") || u.startsWith("\\\\") || u.startsWith("/\\") || u.startsWith("\\/")
          || scheme.startsWith("http"))) {
        out.add(new String[] {"URL_CROSS_ORIGIN_CODE", "item '" + item + "'"});
      }
    }
    if (items.size() > 1) {
      out.add(new String[] {"URLS_FROM_ONE_VALUE", items.size() + " items from one value"});
    }
    // 2. CSS sinks.
    if (a.equals("style") && CSS_FETCH.matcher(cssNormalize(value)).find()) {
      out.add(new String[] {"CSS_FETCH", "css '" + value + "'"});
    }
    // 3. Tree-builder and policy inputs: the parser or the browser reads them before any page code.
    boolean tb = a.equals("is") || a.equals("xmlns") || a.startsWith("xmlns:")
        || (tag.equals("meta") && (a.equals("charset") || a.equals("http-equiv")
            || (a.equals("content") && "content-type".equalsIgnoreCase(el.getAttribute("http-equiv").strip()))))
        || (tag.equals("template") && (a.startsWith("shadowroot") || a.equals("for")))
        || (tag.equals("script") && a.equals("type"));
    if (tb && value.equals(hostile)) {
      out.add(new String[] {"TREE_BUILDER_INPUT", "browser reads '" + value + "'"});
    }
    boolean policy = (tag.equals("iframe") && Set.of("sandbox", "allow", "csp", "allowfullscreen").contains(a))
        || (tag.equals("link") && Set.of("rel", "as", "integrity", "crossorigin").contains(a))
        || (tag.equals("script") && Set.of("integrity", "crossorigin", "nomodule", "nonce").contains(a));
    if (policy && value.equals(hostile)) {
      out.add(new String[] {"POLICY_INPUT", "policy attribute holds '" + value + "'"});
    }
    // 4. Markup: srcdoc is parsed as a whole HTML document.
    if (a.equals("srcdoc") && !value.isEmpty()) {
      out.add(new String[] {"MARKUP_ATTRIBUTE", "srcdoc holds '" + value + "'"});
    }
    // 5. Event handlers: the browser runs the value as script.
    if (a.startsWith("on") && !value.isEmpty()) {
      out.add(new String[] {"EVENT_HANDLER", "on* holds '" + value + "'"});
    }
    // 6. Names outside HTML (#583 rule 3): client code may run what it reads there, so the value
    // must be framework-neutral, whichever code reads the name. Browsers fold A-Z only, so a
    // non-ASCII look-alike (U+212A in "Kind") is a name outside HTML.
    String folded = asciiLower(attrName);
    if (!STANDARD.containsKey(folded) && !folded.startsWith("on") && !neutral(value)) {
      out.add(new String[] {"NON_NEUTRAL_VALUE", attrName + " outside HTML holds '" + value + "'"});
    }
    return out;
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
}
