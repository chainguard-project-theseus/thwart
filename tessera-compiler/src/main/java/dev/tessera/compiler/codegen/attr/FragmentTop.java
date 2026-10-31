package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.FragmentRef;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The elements a {@code ~{...}} used as a {@code Fragment} value writes at its top level, looking
 * through {@code <t:block>}, {@code t:remove="tag"} and nested {@code t:insert}/{@code t:replace}
 * of fragment references (#348). A table part there could end a cell wherever the value is
 * inserted. The verifier composes a {@code Fragment} argument only where the callee inserts the
 * parameter by itself, so this check stays until it composes every insertion (TODO(#561)).
 */
final class FragmentTop {
  /** Elements a browser places by the table rules. */
  static final Set<String> TABLE_PARTS =
      Backports.setOf("table", "caption", "colgroup", "col", "tbody", "thead", "tfoot", "tr", "td", "th");

  /** Lower-case names of the elements written with their tags. */
  final List<String> elements = new ArrayList<>();

  private FragmentTop() {}

  /** The top level of {@code ref}, a fragment reference in the current template used as a {@code Fragment} value. */
  static FragmentTop of(CodegenContext ctx, FragmentRef ref) {
    FragmentTop top = new FragmentTop();
    top.ref(ctx, ctx.template(), ref, 0);
    return top;
  }

  private void inserted(CodegenContext ctx, TemplateIndex.TemplateInfo tpl, Attribute a, int depth) {
    String v = a.value();
    if (v == null || !v.contains("::")) {
      return;
    }
    // Syntax errors are reported where the attribute itself is compiled.
    FragmentRef ref = ctx.parser().parseFragmentRef(v, a.valuePos() != null ? a.valuePos() : a.pos(), new Diagnostics());
    if (ref != null) {
      ref(ctx, tpl, ref, depth);
    }
  }

  private void ref(CodegenContext ctx, TemplateIndex.TemplateInfo tpl, FragmentRef ref, int depth) {
    TemplateIndex.TemplateInfo target =
        ref.template() == null ? tpl : ctx.index().template(ref.template()).orElse(null);
    Element e = null;
    if (target != null && target.fragments().containsKey(ref.selector())) {
      e = target.fragments().get(ref.selector()).element();
    } else if (target != null && ref.template() == null) {
      e = ElementSelection.find(target.document().children(), ref.selector());
    }
    if (e != null && depth <= 8) {
      element(ctx, target, e, depth + 1);
    }
  }

  private void element(CodegenContext ctx, TemplateIndex.TemplateInfo tpl, Element e, int depth) {
    if (e.attribute("t:replace").isPresent()) {
      inserted(ctx, tpl, e.attribute("t:replace").get(), depth);
      return;
    }
    if (!TextInlining.tagNotRendered(e)) {
      elements.add(HtmlParser.asciiLower(e.name()));
      return;
    }
    if (e.attribute("t:insert").isPresent()) {
      inserted(ctx, tpl, e.attribute("t:insert").get(), depth);
      return;
    }
    for (Node n : e.children()) {
      if (n instanceof Element) {
        element(ctx, tpl, (Element) n, depth);
      }
    }
  }
}
