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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The class each template's generated file is named by, by view name. */
    private final Map<String, String> classes;
    /** What the registry is generated from, to add fragment entries to it ({@link #files(Set, Set)}). */
    private final CompilerEnv env;
    private final TemplateIndex index;

    Result(List<GeneratedFile> files, Map<String, Skeleton.TemplateSkeleton> skeletons, Map<String, String> classes,
        CompilerEnv env, TemplateIndex index) {
      this.files = Collections.unmodifiableList(files);
      this.skeletons = Collections.unmodifiableMap(skeletons);
      this.classes = classes;
      this.env = env;
      this.index = index;
    }

    /**
     * {@link #files()}, with the classes of the templates in {@code flowPartials} (view names, as
     * {@code ContextVerifier.flowPartials} returns them) marked as verified {@code FLOW} partials
     * (#396).
     */
    public List<GeneratedFile> files(Set<String> flowPartials) {
      Set<String> marked = new HashSet<>();
      for (String view : flowPartials) {
        if (classes.containsKey(view)) {
          marked.add(classes.get(view));
        }
      }
      List<GeneratedFile> out = new ArrayList<>(files.size());
      for (GeneratedFile f : files) {
        out.add(f.kind() == GeneratedFile.Kind.JAVA && marked.contains(f.name()) ? TemplateGenerator.markFlowPartial(f) : f);
      }
      return out;
    }

    /**
     * {@link #files(Set)}, with a registry that also has an entry for each fragment in {@code
     * servable} ({@code page :: fragment}, as {@code ContextVerifier.servableFragments} returns
     * them) whose parameters a model can supply (#131).
     */
    public List<GeneratedFile> files(Set<String> flowPartials, Set<String> servable) {
      List<GeneratedFile> out = files(flowPartials);
      if (servable.isEmpty()) {
        return out;
      }
      for (GeneratedFile registry : new RegistryGenerator(env, index, servable).generate()) {
        for (int i = 0; i < out.size(); i++) {
          if (out.get(i).kind() == registry.kind() && out.get(i).name().equals(registry.name())) {
            out.set(i, registry);
          }
        }
      }
      return out;
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
    Map<String, String> classes = new HashMap<>();
    for (Document doc : docs) {
      for (GeneratedFile f : generator.generate(doc)) {
        out.add(f);
        if (f.kind() == GeneratedFile.Kind.JAVA) {
          classes.put(doc.source().viewName(), f.name());
        }
      }
      skeletons.put(doc.source().viewName(), generator.skeleton());
    }
    out.addAll(new RegistryGenerator(env, index).generate());
    return new Result(out, skeletons, classes, env, index);
  }
}
