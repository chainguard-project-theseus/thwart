package dev.tessera.compiler.html;

import dev.tessera.compiler.model.Element;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The names a computed attribute may have (#330, security-architecture.md section 5.6): a standard
 * HTML, SVG, MathML or ARIA attribute ({@code attributes.txt}, which names its spec sources), a
 * {@code data-*} name, or a plain {@code [a-z][a-z0-9]*} name on a custom element (a component
 * prop). The framework names (directives, htmx URLs, ...) are classified by {@link Contexts} before
 * this is asked. Static attributes are not checked. Of the standard names, only the inert-text
 * attributes ({@code inert-attributes.txt}) take a plain value ({@link #needsSafeValue}).
 */
final class KnownAttributes {
  private KnownAttributes() {}

  /** The standard names; the value is the rest of the line (the sources). */
  private static final Map<String, String> STANDARD = load("attributes.txt");

  /** The inert-text names; the value is the scope ({@code html}, {@code foreign} or {@code any}) and group. */
  private static final Map<String, String> INERT = load("inert-attributes.txt");

  /** A component prop on a custom element. */
  private static final Pattern PROP = Pattern.compile("[a-z][a-z0-9]*");

  /** Whether {@code name} (any case) may be computed on {@code element}. */
  static boolean accepts(Element element, String name) {
    String a = HtmlParser.asciiLower(name);
    return STANDARD.containsKey(a)
        || (a.startsWith("data-") && a.length() > "data-".length())
        || (isCustomElement(element) && PROP.matcher(a).matches());
  }

  /**
   * Whether {@code name} (any case) is a standard name that is not an inert-text attribute in
   * {@code element}'s namespace (HTML, or SVG/MathML when {@link Element#foreign}), so a plain
   * computed value may not go there. A component prop on a custom element is never restricted, and
   * {@code data-*} names are not standard.
   */
  static boolean needsSafeValue(Element element, String name) {
    String a = HtmlParser.asciiLower(name);
    if (!STANDARD.containsKey(a) || (isCustomElement(element) && PROP.matcher(a).matches())) {
      return false;
    }
    String inert = INERT.get(a);
    if (inert == null) {
      return true;
    }
    String[] fields = inert.split(" ");
    if (fields.length > 2 && !element.foreign()
        && !Arrays.asList(fields[2].split(",")).contains(HtmlParser.asciiLower(element.name()))) {
      return true; // inert only on the HTML elements listed
    }
    return !fields[0].equals("any") && !fields[0].equals(element.foreign() ? "foreign" : "html");
  }

  /** An HTML element whose name has a {@code -}; SVG and MathML names such as {@code font-face} are not. */
  static boolean isCustomElement(Element element) {
    return !element.foreign() && element.name().indexOf('-') >= 0;
  }

  /** A data file: the name, one per line before the first space, and the rest; {@code #} lines are comments. */
  private static Map<String, String> load(String file) {
    Map<String, String> names = new HashMap<>();
    try (InputStream in = KnownAttributes.class.getResourceAsStream(file)) {
      if (in == null) {
        throw new IllegalStateException("dev/tessera/compiler/html/" + file + " is missing");
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int space = line.indexOf(' ');
        names.put(space < 0 ? line : line.substring(0, space), space < 0 ? "" : line.substring(space + 1));
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read dev/tessera/compiler/html/" + file, e);
    }
    return Collections.unmodifiableMap(names);
  }
}
