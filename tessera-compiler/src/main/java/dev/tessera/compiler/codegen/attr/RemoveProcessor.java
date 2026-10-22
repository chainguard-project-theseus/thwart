package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;

/**
 * {@code t:remove="all|body|tag|all-but-first|none"} (literal values only). {@code all-but-first}
 * keeps the first child element and every non-element child (whitespace, comments), as
 * Thymeleaf does.
 */
public final class RemoveProcessor implements AttributeProcessor {

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    String v = attr.value() == null ? "" : attr.value().trim();
    switch (v) {
      case "all":
        plan.remove();
        break;
      case "body":
        plan.replaceBody("", Contexts.text(plan.element()));
        break;
      case "tag":
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

  private static String firstElementOnly(CodegenContext ctx, ElementPlan plan) {
    Element e = plan.element();
    StringBuilder sb = new StringBuilder();
    boolean seenElement = false;
    for (Node n : e.children()) {
      if (n instanceof Element) {
        if (seenElement) {
          continue;
        }
        seenElement = true;
      }
      sb.append(ctx.renderNode(n, plan.scope()));
    }
    return sb.toString();
  }
}
