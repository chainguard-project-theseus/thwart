package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Called once per template: adds {@code public static void fragment_<name>(<params>,
 * RenderContext ctx, Output out)} for every {@code t:fragment} in
 * {@code ctx.template().fragments()}, rendering the fragment element in a scope containing only
 * its declared parameters.
 */
public final class FragmentMethods {
  private FragmentMethods() {}

  private static final String IO = "dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out";

  public static void generate(CodegenContext ctx) {
    for (TemplateIndex.FragmentInfo f : ctx.template().fragments().values()) {
      // Only the declared parameters: no template params, no caller locals (principle 2).
      Scope scope = Scope.empty();
      List<String> decls = new ArrayList<>();
      for (TemplateIndex.Param p : f.params()) {
        String javaName = javaName(p.name());
        scope = scope.with(new Variable(p.name(), p.type(), javaName));
        decls.add(p.javaType() + " " + javaName);
      }
      decls.add(IO);
      String body = ctx.renderElement(f.element(), scope, Set.of("fragment"));
      ctx.addMember(
          "  public static void "
              + f.methodName()
              + "("
              + String.join(", ", decls)
              + ") {\n"
              + body
              + "  }");
    }
  }

  /**
   * Java name of a fragment parameter: always {@code name$}. The suffix keeps a parameter named
   * like a package root ({@code java}, {@code dev}, {@code com}) from obscuring the fully
   * qualified names in the generated body, and like a keyword or {@code ctx}/{@code out} from
   * clashing; it is what {@code TemplateGenerator.javaName} yields for keywords.
   */
  static String javaName(String param) {
    return param + "$";
  }
}
