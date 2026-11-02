package dev.tessera.compiler.codegen.attr;

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
 * Called once per template: adds {@code public static void <method>(<params>, RenderContext ctx,
 * Output out)} ({@link #method}) for every {@code t:fragment} in
 * {@code ctx.template().fragments()}, rendering the fragment element in a scope containing only
 * its declared parameters ({@link CodegenContext#renderFragment}).
 *
 * <p>A fragment with a {@code Fragment} parameter gets no public method (#600): the verifier parses
 * the fragments templates pass where they land, and checks nothing else. Its class and every class
 * that calls it get a private copy instead ({@link CodegenContext#privateFragment}), so only
 * generated code can pass it a {@code Fragment}.
 */
public final class FragmentMethods {
  private FragmentMethods() {}

  /** The trailing {@code RenderContext ctx, Output out} parameters of every render method. */
  static final String IO = "dev.tessera.runtime.RenderContext ctx, dev.tessera.runtime.Output out";

  /** Returns the skeleton of each fragment method, by fragment name. */
  public static Map<String, Skeleton.Seq> generate(CodegenContext ctx) {
    Map<String, Skeleton.Seq> skeletons = new LinkedHashMap<>();
    for (TemplateIndex.FragmentInfo f : ctx.template().fragments().values()) {
      skeletons.put(f.name(), method(ctx, f, takesFragment(f) ? ctx.privateFragment(ctx.template(), f) : null).seq());
    }
    return skeletons;
  }

  /**
   * Adds the method of {@code f}: {@code private static <name>} if {@code name} is set (a
   * {@link CodegenContext#privateFragment}), else the Java-facing {@code public static
   * fragment_<name>}; returns its body.
   */
  public static Emitted method(CodegenContext ctx, TemplateIndex.FragmentInfo f, String name) {
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
    ctx.addMember("  " + (name == null ? "public static void " + f.methodName() : "private static void " + name)
        + "(" + String.join(", ", decls) + ") {\n" + body.java() + "  }");
    return body;
  }

  /** Whether {@code f} has a {@code Fragment} parameter, so each class calling it gets a private copy. */
  static boolean takesFragment(TemplateIndex.FragmentInfo f) {
    for (TemplateIndex.Param p : f.params()) {
      if (ExpressionCompiler.isDeclared(p.type(), Fragment.class.getName())) {
        return true;
      }
    }
    return false;
  }
}
