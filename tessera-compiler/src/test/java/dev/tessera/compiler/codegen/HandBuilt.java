package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Comment;
import dev.tessera.compiler.model.DocType;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.verify.Skeleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Builds template {@link Document}s by hand (with positions computed from the source text) and
 * runs the codegen stages on them inside javac, writing the output through the Filer so the
 * generated classes are compiled and can be rendered through {@link Compilation}. Used to test
 * codegen independently of the HTML and expression parsers.
 */
final class HandBuilt {
  private final String path;
  private final String source;

  HandBuilt(String path, String source) {
    this.path = path;
    this.source = source;
  }

  String viewName() {
    return path.substring(0, path.length() - ".html".length());
  }

  /** Position of the {@code nth} (0-based) occurrence of {@code needle} in the source. */
  SourcePos pos(String needle, int nth) {
    int at = -1;
    for (int i = 0; i <= nth; i++) {
      at = source.indexOf(needle, at + 1);
      if (at < 0) {
        throw new IllegalArgumentException("not found: " + needle);
      }
    }
    return new SourcePos(path, 1, 1).advance(source, at);
  }

  SourcePos pos(String needle) {
    return pos(needle, 0);
  }

  /** {@code name="value"} attribute located at the first occurrence of {@code name="value"}. */
  Attribute attr(String name, String value) {
    String written = name + "=\"" + value + "\"";
    SourcePos p = pos(written);
    return new Attribute(name, value, value, '"', p, p.advance(written, name.length() + 2));
  }

  Attribute bare(String name) {
    return new Attribute(name, null, null, (char) 0, pos(" " + name).advance(" ", 1), null);
  }

  /** Element whose start tag is the {@code nth} occurrence of {@code <name}. */
  Element el(String name, int nth, boolean hasEndTag, List<Attribute> attrs, Node... children) {
    return new Element(name, attrs, List.of(children), false, hasEndTag, pos("<" + name, nth));
  }

  Element el(String name, List<Attribute> attrs, Node... children) {
    return el(name, 0, true, attrs, children);
  }

  Text text(String raw) {
    return new Text(raw, OutputContext.TEXT, pos(raw));
  }

  Comment comment(String raw) {
    return new Comment(raw, pos(raw));
  }

  DocType doctype(String raw) {
    return new DocType(raw, pos(raw));
  }

  Document doc(Node... children) {
    return new Document(new TemplateSource(viewName(), path, source), List.of(children));
  }

  /** Index entry for {@code doc} with {@code params} and no fragments. */
  static TemplateIndex.TemplateInfo info(Document doc, List<TemplateIndex.Param> params) {
    return new TemplateIndex.TemplateInfo(
        doc, ClassNames.className(CompilerEnv.DEFAULT_PACKAGE, doc.source().viewName()), List.of(), params, Map.of());
  }

  /** Result of {@link #run}. */
  record Run(Compilation compilation, Diagnostics diagnostics, List<GeneratedFile> files) {
    /** The reported errors, formatted as {@code file:line:col: error: message}. */
    List<String> errors() {
      return HandBuilt.errors(diagnostics);
    }

    /** Asserts some reported error contains every one of {@code fragments}. */
    Run assertError(String... fragments) {
      HandBuilt.assertError(errors(), fragments);
      return this;
    }
  }

  static List<String> errors(Diagnostics d) {
    return d.all().stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).map(Diagnostic::toString).toList();
  }

  static void assertError(List<String> errors, String... fragments) {
    assertTrue(
        errors.stream().anyMatch(e -> List.of(fragments).stream().allMatch(e::contains)),
        "no error with " + List.of(fragments) + " in " + errors);
  }

  /** Generates {@code doc} alone (no params) and asserts the compilation succeeded. */
  static Compilation compile(Document doc) {
    return run(Compilation.builder(), pe -> List.of(info(doc, List.of()))).compilation().assertSuccess();
  }

  /**
   * Generates every template returned by {@code templates} (plus the registry) inside javac and
   * writes the files through the Filer only if no errors were reported, like the processor does.
   */
  static Run run(Compilation.Builder builder, Function<ProcessingEnvironment, List<TemplateIndex.TemplateInfo>> templates) {
    Diagnostics diagnostics = new Diagnostics();
    List<GeneratedFile> files = new ArrayList<>();
    Map<String, Skeleton.TemplateSkeleton> skeletons = new LinkedHashMap<>();
    Compilation c =
        builder
            .skeletons(skeletons)
            .inProcessor(
                pe -> {
                  CompilerEnv env = Compilation.env(pe, diagnostics);
                  TemplateIndex index = TemplateIndex.of(templates.apply(pe));
                  TemplateGenerator gen =
                      new TemplateGenerator(env, index, new ExpressionParser(), new ExpressionCompiler(env));
                  for (TemplateIndex.TemplateInfo t : index.templates()) {
                    files.addAll(gen.generate(t.document()));
                    skeletons.put(t.viewName(), gen.skeleton());
                  }
                  files.addAll(new RegistryGenerator(env, index).generate());
                  if (!diagnostics.hasErrors()) {
                    Map<Skeleton.Hole, Skeleton.Escaper> escapers = Compilation.unverifiedEscapers(skeletons);
                    files.replaceAll(f -> f.kind() == GeneratedFile.Kind.JAVA
                        ? new GeneratedFile(f.kind(), f.name(), Emitted.resolve(f.content(), escapers)) : f);
                    write(pe, files);
                  }
                })
            .compile();
    return new Run(c, diagnostics, files);
  }

  // Mirrors TesseraProcessor.write.
  private static void write(ProcessingEnvironment pe, List<GeneratedFile> files) {
    try {
      for (GeneratedFile f : files) {
        FileObject fo =
            f.kind() == GeneratedFile.Kind.JAVA
                ? pe.getFiler().createSourceFile(f.name())
                : pe.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", f.name());
        try (Writer w = fo.openWriter()) {
          w.write(f.content());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
