package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.EachSpec;
import dev.tessera.compiler.model.Attribute;
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

    StringBuilder open = new StringBuilder();
    open.append("{ final var ").append(src).append(" = ").append(iterable.code()).append("; ");
    open.append("if (").append(src).append(" != null) { ");
    if (source.getKind() == TypeKind.ARRAY) {
      open.append("final int ").append(size).append(" = ").append(src).append(".length; ");
      open.append("for (int ").append(index).append(" = 0; ").append(index).append(" < ").append(size)
          .append("; ").append(index).append("++) { ");
      open.append("final var ").append(var).append(" = ").append(src).append('[').append(index).append("]; ");
    } else {
      if (isSubtype(ctx, source, "java.util.Map")) {
        open.append("final var ").append(items).append(" = ").append(src).append(".entrySet(); ");
      } else if (isSubtype(ctx, source, "java.util.Collection")) {
        open.append("final var ").append(items).append(" = ").append(src).append("; ");
      } else {
        // A plain Iterable may be single-use and has no size: copy it once.
        String e = ctx.freshName("e");
        open.append("final java.util.List<").append(element).append("> ").append(items)
            .append(" = new java.util.ArrayList<>(); ");
        open.append("for (final ").append(element).append(' ').append(e).append(" : ").append(src).append(") { ")
            .append(items).append(".add(").append(e).append("); } ");
      }
      open.append("final int ").append(size).append(" = ").append(items).append(".size(); ");
      open.append("int ").append(index).append(" = 0; ");
      open.append("for (final var ").append(var).append(" : ").append(items).append(") { ");
    }
    String ws = plan.element().name().equals("t:block") ? null : ctx.whitespaceBefore(plan.element());
    if (ws != null) {
      open.append("if (").append(index).append(" > 0) { out.write(")
          .append(JavaStrings.literal(ws)).append("); } ");
    }
    open.append("final ").append(LOOP_STATUS).append(' ').append(stat).append(" = ")
        .append(LOOP_STATUS).append(".of(").append(index).append(", ").append(size).append("); ");
    String close = source.getKind() == TypeKind.ARRAY ? "} } }" : index + "++; } } }";
    plan.wrap(open.toString(), close);

    TypeMirror statType = ctx.env().elements().getTypeElement(LOOP_STATUS).asType();
    plan.scope(
        plan.scope()
            .with(new Variable(spec.var(), element, var))
            .with(new Variable(statName, statType, stat)));
  }

  private static boolean isSubtype(CodegenContext ctx, TypeMirror t, String name) {
    Types types = ctx.env().types();
    return types.isSubtype(types.erasure(t), types.erasure(ctx.env().elements().getTypeElement(name).asType()));
  }
}
