package dev.tessera.compiler;

import dev.tessera.compiler.codegen.FileErrors;
import dev.tessera.compiler.codegen.RegistryGenerator;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.types.ExpressionCompiler;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile pipeline: parse every template (HTML + t: attributes), index template parameters
 * and fragment signatures, then generate one class per template plus the registry.
 *
 * <pre>
 *   TemplateSource --HtmlParser--> Document --TemplateIndex--> signatures
 *                  --TemplateGenerator (ExpressionParser, ExpressionCompiler, AttributeProcessors)--> Java
 *   all templates  --RegistryGenerator--> TesseraRegistry + META-INF/services entry
 * </pre>
 *
 * Errors are reported to {@link CompilerEnv#diagnostics()}; if any template has an error,
 * nothing is generated (the registry refers to every template class).
 */
public final class TemplateCompiler {
  private final CompilerEnv env;
  private final FileErrors errors;

  public TemplateCompiler(CompilerEnv env) {
    this.errors = new FileErrors(env.diagnostics());
    this.env =
        new CompilerEnv(
            env.elements(), env.types(), errors, env.basePackage(), env.messages(), env.functionNamespaces());
  }

  public List<GeneratedFile> compile(List<TemplateSource> sources) {
    if (sources.isEmpty()) {
      return List.of();
    }
    List<GeneratedFile> out = generate(sources);
    return errors.hasErrors() ? List.of() : out;
  }

  private List<GeneratedFile> generate(List<TemplateSource> sources) {
    HtmlParser html = new HtmlParser();
    List<Document> docs = new ArrayList<>();
    for (TemplateSource src : sources) {
      docs.add(html.parse(src, env.diagnostics()));
    }
    ExpressionParser exprParser = new ExpressionParser();
    ExpressionCompiler exprCompiler = new ExpressionCompiler(env);
    TemplateIndex index = TemplateIndex.build(docs, exprParser, exprCompiler, env);
    TemplateGenerator generator = new TemplateGenerator(env, index, exprParser, exprCompiler);
    List<GeneratedFile> out = new ArrayList<>();
    for (Document doc : docs) {
      out.addAll(generator.generate(doc));
    }
    out.addAll(new RegistryGenerator(env, index).generate());
    return out;
  }
}
