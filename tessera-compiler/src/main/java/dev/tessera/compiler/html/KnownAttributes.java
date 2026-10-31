package dev.tessera.compiler.html;

import dev.tessera.compiler.model.Element;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The names a computed attribute may have (#330, security-architecture.md section 5.6): a standard
 * HTML, SVG, MathML or ARIA attribute ({@code attributes.txt}, which names its spec sources), a
 * {@code data-*} name, or a plain {@code [a-z][a-z0-9]*} name on a custom element (a component
 * prop). The framework names (directives, htmx URLs, ...) are classified by {@link Contexts} before
 * this is asked. Static attributes are not checked.
 */
final class KnownAttributes {
  private KnownAttributes() {}

  private static final Set<String> STANDARD = load();

  /** A component prop on a custom element. */
  private static final Pattern PROP = Pattern.compile("[a-z][a-z0-9]*");

  /** Whether {@code name} (any case) may be computed on {@code element}. */
  static boolean accepts(Element element, String name) {
    String a = HtmlParser.asciiLower(name);
    return STANDARD.contains(a)
        || (a.startsWith("data-") && a.length() > "data-".length())
        || (isCustomElement(element) && PROP.matcher(a).matches());
  }

  /** An HTML element whose name has a {@code -}; SVG and MathML names such as {@code font-face} are not. */
  static boolean isCustomElement(Element element) {
    return !element.foreign() && element.name().indexOf('-') >= 0;
  }

  /** The standard names, one per line before the first space; {@code #} lines are comments. */
  private static Set<String> load() {
    Set<String> names = new HashSet<>();
    try (InputStream in = KnownAttributes.class.getResourceAsStream("attributes.txt")) {
      if (in == null) {
        throw new IllegalStateException("dev/tessera/compiler/html/attributes.txt is missing");
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int space = line.indexOf(' ');
        names.add(space < 0 ? line : line.substring(0, space));
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read dev/tessera/compiler/html/attributes.txt", e);
    }
    return Collections.unmodifiableSet(names);
  }
}
