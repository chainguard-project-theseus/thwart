package dev.tessera.compiler.html;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.Text;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a browser parses the content of each element of a template: as {@link #HTML} markup, as
 * SVG or MathML (foreign content, where {@code <title>}, {@code <style>} and {@code <script>}
 * hold markup and {@code <![CDATA[} is text), inside a {@code <select>} (where some browsers
 * ignore {@code <title>} and {@code <style>} tags and parse their content as markup), or as the
 * raw text of an HTML {@code <title>}, {@code <textarea>}, {@code <noscript>}, ... A fragment's
 * markup reads the same only where it is parsed in the context it was compiled in, so
 * {@code t:insert} and {@code t:replace} compare these contexts.
 *
 * <p>A context is {@link #HTML}, {@code svg}, {@code math}, {@code annotation-xml}, {@code
 * select}, or the name of the raw-text element. SVG {@code foreignObject}, {@code desc} and
 * {@code title}, MathML {@code mi mo mn ms mtext} and {@code <annotation-xml
 * encoding="text/html">} hold HTML.
 *
 * <p>Contexts follow the tags as written, which is how the template is compiled: the children of
 * {@code <svg t:replace>} are SVG content, since they only render as fragments declared there. A
 * {@code <t:block>} has no tag and does not change the context. An element with {@code
 * t:remove="tag"} renders its children without the tag, so where its tag would change the context
 * (an {@code <svg>}, {@code <foreignObject>}, {@code <title>}, ...) its content is {@link
 * #UNWRAPPED}: a browser parses it differently from how it is compiled.
 *
 * <p>No fragment fits in or comes from {@link #UNWRAPPED} or {@link #MIXED} content, or fits in raw
 * text. {@link #MIXED} is an {@code <svg>} or {@code <math>} with a breakout tag such as {@code
 * <p>} in its output (a browser leaves foreign content there), an {@code <annotation-xml>} whose
 * {@code encoding} is computed, and the content of MathML {@code mglyph} and {@code malignmark}
 * (their namespace depends on the parent), and the content of a {@code <noscript>}, {@code
 * <iframe>}, ... in {@code <svg>} or {@code <math>}. Attribute names compare
 * ASCII-case-insensitively, and an attribute that {@code t:<name>} or {@code t:attr*} may set
 * counts as present.
 *
 * <p>Runtime HTML inside a {@link #rawTextElement} is the raw-text rule's error (TemplateGenerator):
 * callers report nothing of their own there ({@link #insideRawText}).
 */
public final class ParsingContexts {
  public static final String HTML = "HTML";

  /** A context no fragment fits in or comes from: see the class comment. */
  static final String MIXED = "mixed";

  /** The content of {@code t:remove="tag"} where the tag changes the context: see the class comment. */
  static final String UNWRAPPED = "unwrapped";

  private static final String SELECT = "select";

  private static final Set<String> RAW_TEXT =
      Backports.setOf(
          "title", "textarea", "script", "style", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext");
  /** Tags a browser ignores inside {@code <select>}, parsing their content as markup, or that start foreign content. */
  private static final Set<String> NOT_IN_SELECT = Backports.setOf("title", "style", "svg", "math");

  private final Map<Element, String> content = new IdentityHashMap<>();
  private final Map<Element, String> place = new IdentityHashMap<>();
  private final Map<Text, String> text = new IdentityHashMap<>();
  /** Elements and text inside a {@link #rawTextElement}. */
  private final Set<Node> insideRawText = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());

  private ParsingContexts() {}

  /** The contexts of every element of {@code document}; its top level is HTML. */
  public static ParsingContexts of(Document document) {
    ParsingContexts contexts = new ParsingContexts();
    contexts.walk(document.children(), HTML, false);
    return contexts;
  }

  /** The context {@code element}'s children are parsed in. */
  public String content(Element element) {
    return content.get(element);
  }

  /** The context {@code element} itself is parsed in: its parent's content. */
  public String place(Element element) {
    return place.get(element);
  }


  /**
   * Whether {@code element} is inside an element whose content a browser reads as text up to its
   * end tag ({@link #rawTextElement}, by name in any namespace). Runtime HTML there is the raw-text
   * rule's error (TemplateGenerator), which also covers {@code element} itself when it is one.
   */
  public boolean insideRawText(Element element) {
    return insideRawText.contains(element);
  }

  /** Whether {@code node} is inside a {@link #rawTextElement}. */
  public boolean insideRawText(Text node) {
    return insideRawText.contains(node);
  }

  /**
   * Whether a browser may read the content of {@code e} as text up to its end tag, the elements
   * TemplateGenerator's raw-text rule checks: {@code <title>}, {@code <textarea>} and {@link
   * Contexts#browserRawText} elements, by name in any namespace: a browser reads {@code <title>} in
   * {@code <svg>} or {@code <math>} as RCDATA in an HTML integration point or after a tag that ends
   * foreign content ({@code <b>}).
   */
  public static boolean rawTextElement(Element e) {
    String name = HtmlParser.asciiLower(e.name());
    return name.equals("title") || name.equals("textarea") || Contexts.browserRawText(name);
  }
  /** The context {@code node}, text of this template, is parsed in; {@link #MIXED} if unknown. */
  public String text(Text node) {
    String context = text.get(node);
    return context == null ? MIXED : context;
  }

  /**
   * Whether {@code element}, declared in this template, parses the same way in {@code context}: its
   * content is in the same context there as where it is compiled. Its tag reads the same in any
   * context, so a whole {@code <svg>} also fits in {@code <svg>} content; an element with {@code
   * t:replace} renders as its replacement, which was checked where the element is. An element
   * declared in HTML content also fits in {@code <select>} content if it holds no {@code <title>},
   * {@code <style>}, {@code <svg>}, {@code <math>}, {@code t:insert}, {@code t:replace}, {@code
   * t:utext} or {@code [(...)]}. Nothing fits in raw text.
   */
  public boolean fits(Element element, String context) {
    String declared = hasReplace(element) ? place(element) : content(element);
    if (isMixed(context) || isMixed(declared) || isRawText(context)) {
      return false;
    }
    if (outputContentOf(context, element).equals(declared)) {
      return true;
    }
    return context.equals(SELECT) && declared.equals(HTML) && place(element).equals(HTML) && selectSafe(element);
  }

  /** {@code HTML}, or the element that sets {@code context}, e.g. {@code <svg>}. */
  public static String describe(String context) {
    if (context.equals(MIXED)) {
      return "mixed <svg>/<math> (one holding an HTML element such as <p>)";
    }
    if (context.equals(UNWRAPPED)) {
      return "t:remove=\"tag\" (on an element whose tag changes how its content is parsed)";
    }
    return context.equals(HTML) ? HTML : "<" + context + ">";
  }

  /** Whether {@code context} is {@link #MIXED} or {@link #UNWRAPPED}: no fragment fits there. */
  public static boolean isMixed(String context) {
    return context.equals(MIXED) || context.equals(UNWRAPPED);
  }

  /** Whether {@code context} is {@link #UNWRAPPED}. */
  public static boolean isUnwrapped(String context) {
    return context.equals(UNWRAPPED);
  }

  /** Whether {@code context} is the raw text of an HTML {@code <title>}, {@code <noscript>}, ... */
  public static boolean isRawText(String context) {
    return RAW_TEXT.contains(context);
  }

  /** Whether {@code context} is {@code <select>} content. */
  public static boolean isSelect(String context) {
    return context.equals(SELECT);
  }

  // What a fragment root, t:remove or <template> actually puts into a <select> is checked on the
  // composed output by the verifier's exclusion (c), SELECT_CONTENT (#305).
  private static boolean selectSafe(Element e) {
    if (NOT_IN_SELECT.contains(HtmlParser.asciiLower(e.name()))) {
      return false;
    }
    for (Attribute a : e.attributes()) {
      String n = a.localName();
      if (a.isTessera() && (n.equals("insert") || n.equals("replace") || n.equals("utext"))) {
        return false;
      }
    }
    for (Node n : e.children()) {
      if (n instanceof Text && ((Text) n).raw().contains("[(")) {
        return false;
      }
      if (n instanceof Element && !selectSafe((Element) n)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Records {@code nodes} parsed in {@code context}; returns whether the output of one of them has a
   * breakout tag in foreign content. {@code raw}: whether {@code nodes} are inside a {@link
   * #rawTextElement}.
   */
  private boolean walk(List<Node> nodes, String context, boolean raw) {
    boolean breakout = false;
    for (Node n : nodes) {
      if (raw) {
        insideRawText.add(n);
      }
      if (n instanceof Text) {
        text.put((Text) n, context);
      }
      if (n instanceof Element) {
        Element e = (Element) n;
        String inner = contentOf(context, e);
        if (isForeign(context) && Contexts.browserRawText(e.name())) {
          // A <noscript> in <svg> holds SVG, but the raw-text rule checks it as raw text: no fragment
          // fits in or comes from it, so nothing in it renders where the raw-text rule does not look.
          inner = MIXED;
        } else if (HtmlParser.omitsTag(e.name(), e.attributes()) && !inner.equals(context) && !isRawText(inner)) {
          // Raw text keeps its context: the raw-text rule checks it with or without the tag.
          inner = UNWRAPPED;
        }
        place.put(e, context);
        content.put(e, inner);
        boolean inside = walk(e.children(), inner, raw || rawTextElement(e)) && rendersChildren(e);
        boolean foreign = isForeign(context);
        if (foreign && !omitsTag(e) && HtmlParser.isBreakoutTag(e.name(), e.attributes())) {
          inside = true;
        }
        if (inside && !foreign && isForeign(inner)) {
          // e is the <svg> or <math> a browser leaves early: nothing in it is where the model says.
          mix(e);
          inside = false;
        }
        breakout |= inside;
      }
    }
    return breakout;
  }

  private void mix(Element e) {
    content.put(e, MIXED);
    for (Node n : e.children()) {
      if (n instanceof Text) {
        text.put((Text) n, MIXED);
      }
      if (n instanceof Element) {
        place.put((Element) n, MIXED);
        mix((Element) n);
      }
    }
  }

  private static boolean isForeign(String context) {
    return context.equals("svg") || context.equals("math") || context.equals("annotation-xml");
  }

  /**
   * Whether the output may keep {@code e}'s children: not when {@code t:insert}, {@code
   * t:replace}, {@code t:text}, {@code t:utext} or {@code t:remove="all"}/{@code "body"} drops them.
   */
  private static boolean rendersChildren(Element e) {
    for (Attribute a : e.attributes()) {
      if (!a.isTessera()) {
        continue;
      }
      String n = a.localName();
      if (n.equals("insert") || n.equals("replace") || n.equals("text") || n.equals("utext")) {
        return false;
      }
      if (n.equals("remove") && a.value() != null) {
        String v = JavaStrings.trimJava(a.value());
        if (v.equals("all") || v.equals("body")) {
          return false;
        }
      }
    }
    return true;
  }

  /** The context of {@code e}'s content where its output lands in {@code context}. */
  private static String outputContentOf(String context, Element e) {
    return omitsTag(e) ? context : contentOf(context, e);
  }

  /** The context {@code e}'s content is compiled in, for {@code e} in {@code context}: its tag as written. */
  private static String contentOf(String context, Element e) {
    if (e.name().equals(Attribute.PREFIX + "block")) {
      return context;
    }
    String name = HtmlParser.asciiLower(e.name());
    if (name.equals("mglyph") || name.equals("malignmark")) {
      return MIXED;
    }
    switch (context) {
      case HTML:
        if (name.equals("svg") || name.equals("math") || name.equals(SELECT)) {
          return name;
        }
        return RAW_TEXT.contains(name) ? name : HTML;
      case "svg":
        return HtmlParser.SVG_HTML_PARENTS.contains(name) ? HTML : "svg";
      case "annotation-xml":
        return name.equals("svg") ? "svg" : contentOf("math", e);
      case "math":
        if (HtmlParser.MATHML_TEXT_PARENTS.contains(name)) {
          return HTML;
        }
        if (name.equals("annotation-xml")) {
          if (computed(e, "encoding")) {
            return MIXED;
          }
          return HtmlParser.htmlAnnotation(HtmlParser.MATHML, name, e.attributes()) ? HTML : "annotation-xml";
        }
        return "math";
      default:
        // Inside raw text a browser sees no elements at all; inside <select> it stays in select.
        return context;
    }
  }

  /** Whether {@code t:<name>} or a {@code t:attr} {@link HtmlParser#attrMaySet may set} {@code name} (lower-case). */
  private static boolean computed(Element e, String name) {
    for (Attribute a : e.attributes()) {
      if (a.isTessera() && (HtmlParser.asciiLower(a.localName()).equals(name) || HtmlParser.attrMaySet(a, name))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the output has no tag for {@code e} but its children (or what replaces it): {@code
   * <t:block>}, {@code t:remove="tag"} (trimmed as RemoveProcessor does), and {@code t:replace},
   * whose fragment lands where {@code e} is.
   */
  private static boolean omitsTag(Element e) {
    return hasReplace(e) || HtmlParser.omitsTag(e.name(), e.attributes());
  }

  private static boolean hasReplace(Element e) {
    for (Attribute a : e.attributes()) {
      if (a.name().equals(Attribute.PREFIX + "replace")) {
        return true;
      }
    }
    return false;
  }
}
