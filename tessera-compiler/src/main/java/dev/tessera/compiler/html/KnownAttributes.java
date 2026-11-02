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

/**
 * The standard attribute names (#330, #584, security-architecture.md section 12.2): HTML, SVG,
 * MathML and ARIA ({@code attributes.txt}, which names its spec sources). Of these, only the
 * inert-text attributes ({@code inert-attributes.txt}) take a plain value ({@link #needsSafeValue}).
 * Every other name ({@code data-*}, a custom element's own attributes, any name outside HTML) takes
 * only a framework-neutral value ({@link Contexts#attribute}, #583 rule 3). Static attributes are
 * not checked.
 */
final class KnownAttributes {
  private KnownAttributes() {}

  /** The standard names; the value is the rest of the line (the sources). */
  private static final Map<String, String> STANDARD = load("attributes.txt");

  /** The inert-text names; the value is the group, then the HTML elements it is inert on, if only some. */
  private static final Map<String, String> INERT = load("inert-attributes.txt");

  /** Whether {@code name} (A-Z folded) is a standard HTML, SVG, MathML or ARIA attribute. */
  static boolean isStandard(String name) {
    return STANDARD.containsKey(HtmlParser.asciiLower(name));
  }

  /**
   * Whether {@code name} (any case) is a standard name that is not an inert-text attribute on
   * {@code element}, so a plain computed value may not go there. No attribute of an SVG or MathML
   * element is inert ({@link Contexts#attribute} forbids them all, #678). The element may be a
   * custom element: its standard attributes are classified like any other element's.
   */
  static boolean needsSafeValue(Element element, String name) {
    String a = HtmlParser.asciiLower(name);
    if (!STANDARD.containsKey(a)) {
      return false;
    }
    String inert = INERT.get(a);
    if (inert == null || element.foreign()) {
      return true;
    }
    String[] fields = inert.split(" ");
    // Inert only on the HTML elements listed, if any are.
    return fields.length > 1 && !Arrays.asList(fields[1].split(",")).contains(HtmlParser.asciiLower(element.name()));
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
