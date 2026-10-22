package dev.tessera.compiler.codegen;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.types.ExpressionCompiler;
import java.util.List;

/**
 * Generates the class for one template (shape in docs/mvp-design.md): walks the node tree,
 * emits static markup as string constants, builds an {@link ElementPlan} per element by running
 * {@link AttributeProcessors} in precedence order, and calls the per-template hooks
 * ({@code TextInlining} for text nodes, {@code CsrfInjector} for {@code <form>},
 * {@code FragmentMethods} once per template). Handles {@code t:params}/{@code t:import} on the
 * root element, {@code <t:block>} (tag omitted), and rejects unknown/misplaced core attributes.
 * Implements {@link CodegenContext}.
 */
public final class TemplateGenerator {
  private final CompilerEnv env;
  private final TemplateIndex index;
  private final ExpressionParser parser;
  private final ExpressionCompiler expressions;

  public TemplateGenerator(
      CompilerEnv env, TemplateIndex index, ExpressionParser parser, ExpressionCompiler expressions) {
    this.env = env;
    this.index = index;
    this.parser = parser;
    this.expressions = expressions;
  }

  /** Returns the generated Java source for {@code document}, or nothing if it had errors. */
  public List<GeneratedFile> generate(Document document) {
    throw new UnsupportedOperationException(
        "TODO(#5): TemplateGenerator.generate " + env.basePackage() + index + parser + expressions);
  }
}
