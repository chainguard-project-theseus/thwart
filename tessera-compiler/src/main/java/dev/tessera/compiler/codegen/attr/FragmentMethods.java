package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.JavaStrings.literal;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Fragment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Called once per template: adds {@code public static void fragment_<name>(<params>,
 * RenderContext ctx, Output out)} for every {@code t:fragment} in
 * {@code ctx.template().fragments()}, rendering the fragment element in a scope containing only
 * its declared parameters ({@link CodegenContext#renderFragment}).
 *
 * <p>A fragment with a {@code Fragment} parameter gets two (#581): {@code fragment_<name>}, for
 * Java, refuses a {@code Fragment} that isn't a {@code Fragment.of} ({@link #requireVerified})
 * and calls {@code fragment$<name>}, which renders and which templates call
 * ({@link #templateMethod}), since the verifier parses the fragments they pass where they land.
 */
public final class FragmentMethods {
  private FragmentMethods() {}

  /** The trailing {@code RenderContext ctx, Output out} parameters of every render method. */
  static final String IO = "dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out";

  /** Returns the skeleton of each fragment method, by fragment name. */
  public static Map<String, Skeleton.Seq> generate(CodegenContext ctx) {
    Map<String, Skeleton.Seq> skeletons = new LinkedHashMap<>();
    for (TemplateIndex.FragmentInfo f : ctx.template().fragments().values()) {
      // Only the declared parameters: no template params, no caller locals (principle 2).
      Scope scope = Scope.empty();
      List<String> decls = new ArrayList<>();
      List<String> names = new ArrayList<>();
      for (TemplateIndex.Param p : f.params()) {
        String javaName = TemplateGenerator.javaName(p.name());
        scope = scope.with(new Variable(p.name(), p.type(), javaName));
        decls.add(p.javaType() + " " + javaName);
        names.add(javaName);
      }
      decls.add(IO);
      names.add("ctx");
      names.add("out");
      Emitted body = ctx.renderFragment(f, scope);
      skeletons.put(f.name(), body.seq());
      String declList = String.join(", ", decls);
      String checks = requireVerified(ctx.template().viewName() + " :: " + f.name(), f.params());
      if (!checks.isEmpty()) {
        ctx.addMember(
            "  public static void " + f.methodName() + "(" + declList + ") {\n" + checks + "    "
                + templateMethod(f) + "(" + String.join(", ", names) + ");\n  }");
      }
      ctx.addMember("  public static void " + templateMethod(f) + "(" + declList + ") {\n" + body.java() + "  }");
    }
    return skeletons;
  }

  /**
   * The method a template calls for {@code f}: {@code fragment$<name>} if it has a {@code Fragment}
   * parameter, else {@code fragment_<name>}.
   */
  static String templateMethod(TemplateIndex.FragmentInfo f) {
    for (TemplateIndex.Param p : f.params()) {
      if (ExpressionCompiler.isDeclared(p.type(), Fragment.class.getName())) {
        return "fragment$" + f.name();
      }
    }
    return f.methodName();
  }

  /**
   * Statements that refuse, at render time, a {@code Fragment} in {@code params} that Java built
   * without {@code Fragment.of} ({@code Fragment.requireVerified}, #581); empty if none is a
   * {@code Fragment}. {@code where} names the template or fragment in the message.
   */
  public static String requireVerified(String where, List<TemplateIndex.Param> params) {
    StringBuilder sb = new StringBuilder();
    for (TemplateIndex.Param p : params) {
      if (ExpressionCompiler.isDeclared(p.type(), Fragment.class.getName())) {
        sb.append("    dev.tessera.runtime.Fragment.requireVerified(")
            .append(TemplateGenerator.javaName(p.name()))
            .append(", ")
            .append(literal(where))
            .append(", ")
            .append(literal(p.name()))
            .append(");\n");
      }
    }
    return sb.toString();
  }
}
