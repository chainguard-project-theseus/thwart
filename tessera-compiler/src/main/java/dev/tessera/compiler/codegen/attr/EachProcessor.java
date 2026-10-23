package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.EachSpec;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Variable;
import dev.tessera.runtime.LoopStatus;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * {@code t:each="o, stat : items"}: wraps the element in a loop over an Iterable, array or Map
 * (entries). Declares the loop variable and the status variable ({@code LoopStatus}; implicit
 * name {@code oStat} when omitted). A {@code null} iterable renders zero iterations. Both
 * variables are visible to the element (including its later attributes) and its children only.
 *
 * <p>Like Thymeleaf, the whitespace text right before the element is written again before every
 * iteration after the first, so {@code <tbody>\n<tr t:each>} renders {@code </tr>\n<tr>}
 * between rows. Thymeleaf does not do this for {@code t:block}.
 */
public final class EachProcessor implements AttributeProcessor {
  private static final String LOOP_STATUS = LoopStatus.class.getName();

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.value() == null) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " needs a value, e.g. t:each=\"item : items\"");
      return;
    }
    EachSpec spec = ctx.parser().parseEach(attr.value(), attr.valuePos(), ctx.diagnostics());
    if (spec == null) {
      return;
    }
    String statName = spec.statVar() != null ? spec.statVar() : spec.var() + "Stat";
    if (statName.equals(spec.var())) {
      ctx.diagnostics().error(spec.pos(), "t:each loop variable and status variable must have different names, e.g."
              + " t:each=\"item, itemStat : items\"");
      return;
    }
    CompiledExpr iterable = ctx.expressions().compile(spec.iterable(), plan.scope());
    if (iterable == null) {
      return;
    }
    TypeMirror element = ctx.expressions().elementType(iterable);
    if (element == null) {
      return;
    }
    TypeMirror source = iterable.type();
    String src = ctx.freshName("each");
    String items = ctx.freshName("items");
    String size = ctx.freshName("size");
    String index = ctx.freshName("i");
    String var = ctx.freshName(spec.var());
    String stat = ctx.freshName(statName);
    boolean needsStatus = mayUseStatus(plan.element(), attr, statName);

    StringBuilder open = new StringBuilder();
    String elementType = WithProcessor.sourceType(element);
    open.append("{ final ").append(WithProcessor.sourceType(source)).append(' ').append(src).append(" = ")
        .append(iterable.code()).append("; ");
    open.append("if (").append(src).append(" != null) { ");
    if (source.getKind() == TypeKind.ARRAY) {
      open.append("final int ").append(size).append(" = ").append(src).append(".length; ");
      open.append("for (int ").append(index).append(" = 0; ").append(index).append(" < ").append(size)
          .append("; ").append(index).append("++) { ");
      open.append("final ").append(elementType).append(' ').append(var).append(" = ").append(src).append('[')
          .append(index).append("]; ");
    } else {
      if (isSubtype(ctx, source, "java.util.Map")) {
        // For Map<K, ? extends V> the entries are Entry<K, capture>, which Java cannot name. Templates only
        // read entries (no setValue), so viewing them as Entry<K, V> is sound.
        String entries = "java.util.Collection<" + elementType + ">";
        open.append("@java.lang.SuppressWarnings(\"unchecked\") final ").append(entries).append(' ').append(items)
            .append(" = (").append(entries).append(") (java.util.Collection<?>) ").append(src).append(".entrySet(); ");
      } else if (isSubtype(ctx, source, "java.util.Collection")) {
        open.append("final java.util.Collection<? extends ").append(elementType).append("> ").append(items)
            .append(" = ").append(src).append("; ");
      } else if (!needsStatus) {
        open.append("final java.lang.Iterable<? extends ").append(elementType).append("> ").append(items)
            .append(" = ").append(src).append("; ");
      } else {
        // A plain Iterable may be single-use and has no size: copy it once.
        String e = ctx.freshName("e");
        open.append("final java.util.List<").append(elementType).append("> ").append(items)
            .append(" = new java.util.ArrayList<>(); ");
        open.append("for (final ").append(elementType).append(' ').append(e).append(" : ").append(src).append(") { ")
            .append(items).append(".add(").append(e).append("); } ");
      }
      if (needsStatus) {
        open.append("final int ").append(size).append(" = ").append(items).append(".size(); ");
      }
      open.append("int ").append(index).append(" = 0; ");
      open.append("for (final ").append(elementType).append(' ').append(var).append(" : ").append(items).append(") { ");
    }
    String ws = plan.element().name().equals("t:block") ? null : ctx.whitespaceBefore(plan.element());
    if (ws != null) {
      open.append("if (").append(index).append(" > 0) { out.write(")
          .append(JavaStrings.literal(ws)).append("); } ");
    }
    if (needsStatus) {
      open.append("final ").append(LOOP_STATUS).append(' ').append(stat).append(" = ")
          .append(LOOP_STATUS).append(".of(").append(index).append(", ").append(size).append("); ");
    }
    String close = source.getKind() == TypeKind.ARRAY ? "} } }" : index + "++; } } }";
    plan.wrap(open.toString(), close);

    TypeMirror statType = ctx.env().elements().getTypeElement(LOOP_STATUS).asType();
    plan.scope(
        plan.scope()
            .with(new Variable(spec.var(), element, var))
            .with(new Variable(statName, statType, stat)));
  }

  /**
   * Conservative absence check: attributes are already entity-decoded, and text inlining parses
   * the raw text. Substring matches deliberately include literals and shadowed names. Fragment
   * selections can capture the current scope while rendering an element outside this subtree,
   * so keep status whenever a fragment can be selected, even without a local name reference.
   */
  private static boolean mayUseStatus(Node node, Attribute declaration, String name) {
    if (node instanceof Text) {
      String raw = ((Text) node).raw();
      return raw.contains(name) || raw.contains("~{");
    }
    if (node instanceof Element) {
      Element element = (Element) node;
      for (Attribute a : element.tesseraAttributes()) {
        if (a == declaration) {
          continue;
        }
        if (a.localName().equals("insert") || a.localName().equals("replace")
            || (a.value() != null && (a.value().contains(name) || a.value().contains("~{")))) {
          return true;
        }
      }
      for (Node child : element.children()) {
        if (mayUseStatus(child, declaration, name)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isSubtype(CodegenContext ctx, TypeMirror t, String name) {
    Types types = ctx.env().types();
    return types.isSubtype(types.erasure(t), types.erasure(ctx.env().elements().getTypeElement(name).asType()));
  }
}
