package dev.tessera.compiler;

import dev.tessera.compiler.codegen.RegistryGenerator;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Errors are reported to {@link CompilerEnv#diagnostics()}. Files are generated even when errors
 * were reported; the caller must not write any output if the sink has errors (the registry refers
 * to every template class).
 */
public final class TemplateCompiler {
  private final CompilerEnv env;

  public TemplateCompiler(CompilerEnv env) {
    this.env = env;
  }

  public List<GeneratedFile> compile(List<TemplateSource> sources) {
    return run(sources).files();
  }

  /** The generated files and, in memory only, the skeleton of every template (#366). */
  public static final class Result {
    private final List<GeneratedFile> files;
    private final Map<String, Skeleton.TemplateSkeleton> skeletons;

    Result(List<GeneratedFile> files, Map<String, Skeleton.TemplateSkeleton> skeletons) {
      this.files = Collections.unmodifiableList(files);
      this.skeletons = Collections.unmodifiableMap(skeletons);
    }

    public List<GeneratedFile> files() {
      return files;
    }

    /**
     * What each template's class writes, by view name: recorded where the generator writes it,
     * so it is exact ({@link Skeleton}). Meaningful only if no errors were reported.
     */
    public Map<String, Skeleton.TemplateSkeleton> skeletons() {
      return skeletons;
    }
  }

  /** As {@link #compile}, also returning the skeletons. */
  public Result run(List<TemplateSource> sources) {
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
    Map<String, Skeleton.TemplateSkeleton> skeletons = new LinkedHashMap<>();
    for (Document doc : docs) {
      out.addAll(generator.generate(doc));
      skeletons.put(doc.source().viewName(), generator.skeleton());
    }
    out.addAll(new RegistryGenerator(env, index).generate());
    return new Result(out, skeletons);
  }
}
