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
import dev.tessera.compiler.model.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What {@code t:insert}/{@code t:replace} writes at the top level of where it lands, looking
 * through {@code <t:block>}, {@code t:remove="tag"} and nested {@code t:insert}/{@code t:replace}
 * of fragment references (#348). Used to keep table parts and computed text out of the places a
 * browser would move them: inside a table it moves text in front of the table, and a table part
 * such as {@code <tr>} inside a cell ends the cell.
 */
public final class FragmentTop {
  /** Elements a browser places by the table rules. */
  public static final Set<String> TABLE_PARTS =
      Backports.setOf("table", "caption", "colgroup", "col", "tbody", "thead", "tfoot", "tr", "td", "th");

  /** Lower-case names of the elements written with their tags. */
  public final List<String> elements = new ArrayList<>();
  /** Whether it writes computed text, or a {@code Fragment} value, which has no table parts at its top (#348). */
  public boolean text;
  /** Whether a reference could not be followed; the caller must reject. */
  public boolean unknown;

  private FragmentTop() {}

  /** Whether {@code e} is written without its tags: {@code <t:block>} or a static {@code t:remove="tag"}. */
  public static boolean tagNotRendered(Element e) {
    return e.name().equals("t:block")
        || e.attribute("t:remove").map(Attribute::value).map(String::trim).filter("tag"::equals).isPresent();
  }

  /** The top level written by {@code attr}, a {@code t:insert} or {@code t:replace} in the current template. */
  public static FragmentTop of(CodegenContext ctx, Attribute attr) {
    FragmentTop top = new FragmentTop();
    top.inserted(ctx, ctx.template(), attr, 0);
    return top;
  }

  /** The top level of {@code ref}, a fragment reference in the current template used as a {@code Fragment} value. */
  static FragmentTop of(CodegenContext ctx, FragmentRef ref) {
    FragmentTop top = new FragmentTop();
    top.ref(ctx, ctx.template(), ref, 0);
    return top;
  }

  private void inserted(CodegenContext ctx, TemplateIndex.TemplateInfo tpl, Attribute a, int depth) {
    String v = a.value();
    if (v == null || !v.contains("::")) {
      text = true;
      return;
    }
    // Syntax errors are reported where the attribute itself is compiled.
    FragmentRef ref = ctx.parser().parseFragmentRef(v, a.valuePos() != null ? a.valuePos() : a.pos(), new Diagnostics());
    if (ref == null) {
      unknown = true;
      return;
    }
    ref(ctx, tpl, ref, depth);
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
    if (e == null || depth > 8) {
      unknown = true;
      return;
    }
    element(ctx, target, e, depth + 1);
  }

  private void element(CodegenContext ctx, TemplateIndex.TemplateInfo tpl, Element e, int depth) {
    if (e.attribute("t:replace").isPresent()) {
      inserted(ctx, tpl, e.attribute("t:replace").get(), depth);
      return;
    }
    if (!tagNotRendered(e)) {
      elements.add(HtmlParser.asciiLower(e.name()));
      return;
    }
    if (e.attribute("t:text").isPresent() || e.attribute("t:utext").isPresent() || e.attribute("t:errors").isPresent()) {
      text = true;
    }
    if (e.attribute("t:insert").isPresent()) {
      inserted(ctx, tpl, e.attribute("t:insert").get(), depth);
      return;
    }
    for (Node n : e.children()) {
      if (n instanceof Text && TextInlining.firstHole(((Text) n).raw()) >= 0) {
        text = true;
      } else if (n instanceof Element) {
        element(ctx, tpl, (Element) n, depth);
      }
    }
  }
}
