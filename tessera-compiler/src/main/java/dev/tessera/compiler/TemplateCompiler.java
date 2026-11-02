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

  /** The generated files and, in memory only, the skeleton of every template (#366). */
  public static final class Result {
    private final List<GeneratedFile> files;
    private final Map<String, Skeleton.TemplateSkeleton> skeletons;
    /** What the registry is generated from, to add fragment entries to it ({@link #files(Set)}). */
    private final CompilerEnv env;
    private final TemplateIndex index;
    /** Every private fragment copy, with the fragment it copies ({@code TemplateGenerator#copies}). */
    private final List<Map.Entry<Skeleton.FragmentId, Skeleton.Seq>> copies;

    Result(List<GeneratedFile> files, Map<String, Skeleton.TemplateSkeleton> skeletons,
        CompilerEnv env, TemplateIndex index, List<Map.Entry<Skeleton.FragmentId, Skeleton.Seq>> copies) {
      this.files = Collections.unmodifiableList(files);
      this.skeletons = Collections.unmodifiableMap(skeletons);
      this.env = env;
      this.index = index;
      this.copies = copies;
    }

    /**
     * Throws if a private fragment copy writes another skeleton than its fragment: the verifier
     * checks the fragment, so the copy must write exactly that.
     */
    private void checkCopies() {
      for (Map.Entry<Skeleton.FragmentId, Skeleton.Seq> copy : copies) {
        Skeleton.Seq original = skeletons.get(copy.getKey().view()).fragments().get(copy.getKey().name());
        if (!original.toString().equals(copy.getValue().toString())) {
          throw new IllegalStateException("the copy of " + copy.getKey() + " writes another skeleton than the fragment");
        }
      }
    }

    /**
     * {@link #files()}, checked that each private fragment copy writes its fragment's skeleton, with
     * a registry that also has an entry for each fragment in {@code servable} ({@code page ::
     * fragment}, as {@code ContextVerifier.servableFragments} returns them) whose parameters a model
     * can supply (#131).
     */
    public List<GeneratedFile> files(Set<String> servable) {
      checkCopies();
      List<GeneratedFile> out = new ArrayList<>(files);
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

    /** The generated files, unchecked and without the registry entries of {@link #files(Set)}. */
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

  /** Parses and generates {@code sources}: the generated files and the skeletons. */
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
    List<Map.Entry<Skeleton.FragmentId, Skeleton.Seq>> copies = new ArrayList<>();
    for (Document doc : docs) {
      out.addAll(generator.generate(doc));
      skeletons.put(doc.source().viewName(), generator.skeleton());
      copies.addAll(generator.copies());
    }
    out.addAll(new RegistryGenerator(env, index).generate());
    return new Result(out, skeletons, env, index, copies);
  }
}
