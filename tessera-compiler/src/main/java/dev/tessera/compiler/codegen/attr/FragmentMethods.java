package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Element;
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

  /** The trailing {@code RenderContext ctx, Output out} parameters of every render method. */
  static final String IO = "dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out";

  public static void generate(CodegenContext ctx) {
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
      Element foreign = Contexts.foreignTitleOrTextarea(f.element());
      if (foreign != null) {
        ctx.diagnostics()
            .error(
                foreign.pos(),
                "<" + foreign.name() + "> in t:fragment '" + f.name() + "' is SVG/MathML content only inside its <svg>"
                    + " or <math>: inserted into HTML a browser would parse it as RCDATA; put t:fragment on the <svg> or"
                    + " <math> element that keeps its tag");
      }
      String body = ctx.renderElement(f.element(), scope, Backports.setOf("fragment"));
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

}
