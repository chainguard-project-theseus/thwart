package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Called once per template: adds {@code public static void fragment_<name>(<params>,
 * RenderContext ctx, Output out)} for every {@code t:fragment} in
 * {@code ctx.template().fragments()}, rendering the fragment element in a scope containing only
 * its declared parameters ({@link CodegenContext#renderFragment}).
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
      for (TemplateIndex.Param p : f.params()) {
        String javaName = TemplateGenerator.javaName(p.name());
        scope = scope.with(new Variable(p.name(), p.type(), javaName));
        decls.add(p.javaType() + " " + javaName);
      }
      decls.add(IO);
      Emitted body = ctx.renderFragment(f, scope);
      skeletons.put(f.name(), body.seq());
      ctx.addMember(
          "  public static void "
              + f.methodName()
              + "("
              + String.join(", ", decls)
              + ") {\n"
              + body.java()
              + "  }");
    }
    return skeletons;
  }

}
