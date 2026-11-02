package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code t:remove="all|body|tag|all-but-first|none"} (literal values only). {@code all-but-first}
 * keeps the first child element and every non-element child (whitespace, comments), as
 * Thymeleaf does. {@code tag} is rejected on {@code <svg>}, {@code <math>} and every element
 * inside them, and on raw-text elements ({@link HtmlParser#isRawText}).
 */
public final class RemoveProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    String v = attr.value() == null ? "" : JavaStrings.trimJava(attr.value());
    switch (v) {
      case "all":
        plan.remove();
        break;
      case "body":
        plan.replaceBody(Emitted.EMPTY, Contexts.text(plan.element()));
        break;
      case "tag":
        String tag = plan.element().name();
        if (!Boolean.FALSE.equals(inSvgOrMath(ctx.template().document().children(), plan.element(), false))) {
          // Without the tag a browser parses the content as HTML where Tessera compiled it as SVG or
          // MathML, or the reverse: <svg t:remove="tag"><text><![CDATA[ ends at the first > in HTML.
          ctx.diagnostics()
              .error(
                  attr.valuePos() != null ? attr.valuePos() : attr.pos(),
                  "t:remove=\"tag\" is not allowed on <" + tag + ">: it is <svg> or <math> or inside one, and"
                      + " without the tag a browser would parse its content as HTML, not as SVG or MathML (or the"
                      + " reverse); keep the tag, or use t:remove=\"all\" to drop the element");
        } else if (HtmlParser.isRawText(tag)) {
          // Without its tags the raw text would be parsed as HTML, with holes escaped for the
          // wrong context: <title t:remove="tag"><script>[[s]]</script></title> runs s.
          ctx.diagnostics()
              .error(
                  attr.valuePos() != null ? attr.valuePos() : attr.pos(),
                  "t:remove=\"tag\" is not allowed on <" + tag + ">: its content is text only inside <" + tag
                      + ">, and without the tags a browser would parse it as HTML; keep the tag, or use"
                      + " t:remove=\"all\" to drop the element");
        }
        // Also after an error: nothing is written then, and the skeleton keeps what the template
        // asks for, for the verifier's own check.
        plan.omitTag();
        break;
      case "all-but-first":
        plan.replaceBody(firstElementOnly(ctx, plan), Contexts.text(plan.element()));
        break;
      case "none":
        break;
      default:
        ctx.diagnostics()
            .error(
                attr.valuePos() != null ? attr.valuePos() : attr.pos(),
                "t:remove must be one of all, body, tag, all-but-first, none (got '" + v + "')");
    }
  }

  /**
   * Whether {@code target} (compared by identity) is an {@code <svg>} or {@code <math>} element or
   * inside one, matched by name in any case; null if it is not among {@code nodes}.
   */
  private static Boolean inSvgOrMath(List<Node> nodes, Element target, boolean inside) {
    for (Node n : nodes) {
      if (!(n instanceof Element)) {
        continue;
      }
      Element e = (Element) n;
      String name = HtmlParser.asciiLower(e.name());
      boolean foreign = inside || name.equals("svg") || name.equals("math");
      if (e == target) {
        return foreign;
      }
      Boolean found = inSvgOrMath(e.children(), target, foreign);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static Emitted firstElementOnly(CodegenContext ctx, ElementPlan plan) {
    Element e = plan.element();
    List<Emitted> sb = new ArrayList<>();
    boolean seenElement = false;
    for (Node n : e.children()) {
      if (n instanceof Element) {
        if (seenElement) {
          continue;
        }
        seenElement = true;
      }
      sb.add(ctx.renderNode(n, plan.scope()));
    }
    return Emitted.concat(sb.toArray(new Emitted[0]));
  }
}
