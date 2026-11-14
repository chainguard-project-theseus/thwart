package dev.chainguard.thwart.compiler.html;

import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Document;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.model.Node;
import dev.chainguard.thwart.compiler.model.Text;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a browser parses the content of each element of a template: as {@link #HTML} markup, as
 * SVG or MathML (foreign content, where {@code <title>}, {@code <style>} and {@code <script>}
 * hold markup and {@code <![CDATA[} starts a CDATA section), inside a {@code <select>} (where
 * some browsers ignore {@code <title>} and {@code <style>} tags and parse their content as
 * markup), or as the raw text of an HTML {@code <title>}, {@code <textarea>}, {@code <noscript>},
 * ... Callers use these contexts to reject runtime HTML ({@code t:utext}, {@code [(...)]}, a
 * {@code Fragment} value) outside HTML content and to find uncalled fragments declared where none
 * renders. Whether a called fragment's markup reads the same where it lands is the verifier's
 * check ({@code ContextVerifier}), which replays it in the caller's real parser state.
 *
 * <p>A context is {@link #HTML}, {@code svg}, {@code math}, {@code select}, or the name of the
 * raw-text element. Foreign content is by tag: everything under {@code <svg>} or {@code <math>} is
 * {@code svg} or {@code math} content (integration points are the verifier's to model).
 *
 * <p>Contexts follow the tags as written, which is how the template is compiled: the children of
 * {@code <svg t:replace>} are SVG content, since they only render as fragments declared there. A
 * {@code <t:block>} has no tag and does not change the context. An element with {@code
 * t:remove="tag"} renders its children without the tag, so where its tag would change the context
 * (an {@code <svg>}, {@code <title>}, ...) its content is {@link
 * #UNWRAPPED}: a browser parses it differently from how it is compiled.
 *
 * <p>Runtime HTML inside a {@link #rawTextElement} is the raw-text rule's error (TemplateGenerator):
 * callers report nothing of their own there ({@link #insideRawText}).
 */
public final class ParsingContexts {
  public static final String HTML = "HTML";

  /** The content of {@code t:remove="tag"} where the tag changes the context: see the class comment. */
  static final String UNWRAPPED = "unwrapped";

  private static final String SELECT = "select";

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
   * Contexts#browserRawText} elements, by name in any namespace (a browser reads an SVG {@code
   * <title>} as RCDATA after a tag that ends foreign content).
   */
  public static boolean rawTextElement(Element e) {
    String name = HtmlParser.asciiLower(e.name());
    return name.equals("title") || name.equals("textarea") || Contexts.browserRawText(name);
  }
  /** The context {@code node}, text of this template, is parsed in. */
  public String text(Text node) {
    String context = text.get(node);
    if (context == null) {
      throw new IllegalStateException("text outside this template");
    }
    return context;
  }

  /** {@code HTML}, or the element that sets {@code context}, e.g. {@code <svg>}. */
  public static String describe(String context) {
    if (context.equals(UNWRAPPED)) {
      return "t:remove=\"tag\" (on an element whose tag changes how its content is parsed)";
    }
    return context.equals(HTML) ? HTML : "<" + context + ">";
  }

  /**
   * Whether {@code context} is the raw text of an HTML {@code <title>}, {@code <noscript>}, ...: an
   * element {@link HtmlParser#isRawText} or {@link Contexts#browserRawText} names.
   */
  public static boolean isRawText(String context) {
    return HtmlParser.isRawText(context) || Contexts.browserRawText(context);
  }

  /**
   * Records {@code nodes} parsed in {@code context}. {@code raw}: whether {@code nodes} are inside a
   * {@link #rawTextElement}.
   */
  private void walk(List<Node> nodes, String context, boolean raw) {
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
        if (HtmlParser.omitsTag(e.name(), e.attributes()) && !inner.equals(context) && !isRawText(inner)) {
          // Raw text keeps its context: the raw-text rule checks it with or without the tag.
          inner = UNWRAPPED;
        }
        place.put(e, context);
        content.put(e, inner);
        walk(e.children(), inner, raw || rawTextElement(e));
      }
    }
  }

  /** The context {@code e}'s content is compiled in, for {@code e} in {@code context}: its tag as written. */
  private static String contentOf(String context, Element e) {
    if (e.name().equals(Attribute.PREFIX + "block")) {
      return context;
    }
    String name = HtmlParser.asciiLower(e.name());
    switch (context) {
      case HTML:
        if (name.equals("svg") || name.equals("math") || name.equals(SELECT)) {
          return name;
        }
        return isRawText(name) ? name : HTML;
      default:
        // Inside raw text a browser sees no elements at all; inside <select> it stays in select, and
        // under <svg> or <math> in foreign content (by tag: see HtmlParser).
        return context;
    }
  }
}
