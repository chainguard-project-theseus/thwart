package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import java.util.List;

/**
 * {@code t:remove="all|tag"} (literal values only). Thymeleaf's {@code body}, {@code all-but-first}
 * and {@code none} are build errors that name the static edit with the same result. {@code tag} is
 * rejected on {@code <svg>}, {@code <math>} and every element inside them, and on raw-text elements
 * ({@link HtmlParser#isRawText}).
 */
public final class RemoveProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    String v = attr.value() == null ? "" : JavaStrings.trimJava(attr.value());
    switch (v) {
      case "all":
        plan.remove();
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
      default:
        ctx.diagnostics().error(attr.valuePos() != null ? attr.valuePos() : attr.pos(), unsupported(v));
    }
  }

  /** The error for a value other than all and tag, with the static edit that gives the same page. */
  private static String unsupported(String v) {
    switch (v) {
      case "body":
        return "t:remove=\"body\" is not supported; delete the element's content in the template instead";
      case "all-but-first":
        return "t:remove=\"all-but-first\" is not supported; delete every child element but the first in the"
            + " template instead";
      case "none":
        return "t:remove=\"none\" is not supported; delete the attribute";
      default:
        return "t:remove must be all or tag (got '" + v + "')";
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
}
