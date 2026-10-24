package dev.tessera.compiler.processor;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.TemplateCompiler;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.messages.MessageBundle;
import dev.tessera.runtime.TemplateFunctions;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * javac entry point. Discovers templates under {@code -Atessera.templates}, compiles them with
 * {@link TemplateCompiler}, writes the generated sources and resources through the Filer, and
 * reports diagnostics as {@code file:line:col: message} javac errors. If any template has an
 * error, nothing is written. Never claims annotations, so other processors are unaffected.
 *
 * <p>Rounds (#233): it compiles the templates in the first round and, if that succeeds, is done.
 * If there are errors it says nothing and tries again in each later round, reporting the errors of
 * the last round ({@code processingOver}). Processors that add members to existing classes, such
 * as Lombok, may run after Tessera in a round or (Lombok 1.16) not finish in the first round; javac
 * re-enters all classes from the modified trees before each new round, so a later attempt sees the
 * generated getters whatever the processor order. A compile that succeeded before Lombok finished
 * is checked again in the last round; if the output would differ the build fails.
 *
 * <p>Function index (#118): for each {@code @TemplateFunctions("ns")} class in this compilation it
 * writes {@code META-INF/tessera/functions/ns} (the class name), and a namespace the templates use
 * that is not in this compilation is looked up there on the classpath. So test compilation and
 * incremental builds, which compile templates without recompiling the function classes, still
 * find them (Maven puts the main output directory on both classpaths).
 */
public final class TesseraProcessor extends AbstractProcessor {
  private static final String FUNCTIONS_ANNOTATION = TemplateFunctions.class.getName();
  static final String FUNCTION_INDEX = "META-INF/tessera/functions/";

  private boolean done;
  private ProcessorOptions options;
  private boolean warnedSkippedMessages;
  /** What the processor wrote, compared with a last-round compile (#233), or null. */
  private List<GeneratedFile> written;
  private final Set<String> functionClassNames = new LinkedHashSet<>();

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Backports.setOf("*");
  }

  @Override
  public Set<String> getSupportedOptions() {
    return Backports.setOf(ProcessorOptions.TEMPLATES, ProcessorOptions.PACKAGE, ProcessorOptions.MESSAGES);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (done) {
      if (written != null && round.processingOver()) {
        checkUnchanged();
      }
      return false;
    }
    collectFunctionClasses(round);
    Messager messager = processingEnv.getMessager();
    ProcessorOptions options = ProcessorOptions.parse(processingEnv.getOptions());
    String optionError = optionError(options);
    if (optionError != null) {
      done = true;
      messager.printMessage(Kind.ERROR, optionError);
      return false;
    }
    this.options = options;
    try {
      Diagnostics diagnostics = new Diagnostics();
      List<TypeElement> functionClasses = functionClasses();
      List<GeneratedFile> files = compile(diagnostics, functionClasses);
      if (diagnostics.hasErrors() && !round.processingOver()) {
        // #233: another processor (Lombok) may still be adding members to the classes the templates
        // read; javac re-enters every class before the next round, so try again then and only
        // report errors in the last round.
        return false;
      }
      done = true;
      files.addAll(functionIndex(functionClasses));
      for (Diagnostic d : diagnostics.all()) {
        messager.printMessage(kind(d.severity()), d.toString());
      }
      // The only place that enforces "nothing is written on error".
      if (!diagnostics.hasErrors()) {
        write(files);
        written = files;
      }
    } catch (IOException | RuntimeException e) {
      done = true;
      messager.printMessage(Kind.ERROR, "Tessera: internal compiler error: " + describe(e));
    }
    return false;
  }

  private List<GeneratedFile> compile(Diagnostics diagnostics, List<TypeElement> functionClasses) throws IOException {
    List<TemplateSource> sources = TemplateDiscovery.discover(options.templates());
    MessageBundle messages =
        options.messages().isEmpty() ? MessageBundle.unchecked() : MessageBundle.load(options.messages());
    if (!warnedSkippedMessages) {
      warnedSkippedMessages = true;
      for (Path skipped : messages.skipped()) {
        processingEnv.getMessager().printMessage(
            Kind.WARNING,
            "Tessera: message file " + skipped + " is not loaded: its name does not end in a locale"
                + " (_fr, _pt_BR, ...), so its keys are not checked and its HTML messages are escaped");
      }
    }
    CompilerEnv env =
        new CompilerEnv(
            processingEnv.getElementUtils(),
            processingEnv.getTypeUtils(),
            diagnostics,
            options.basePackage(),
            messages,
            functionClasses,
            this::indexedFunctionClass);
    return new ArrayList<>(new TemplateCompiler(env).compile(sources));
  }

  /**
   * #233: a compile that succeeded before another processor finished adding members can still
   * be wrong without an error (script JSON lists the getters it sees, even none). The Filer cannot
   * rewrite files, so compile once more in the last round and fail the build if the output changed.
   */
  private void checkUnchanged() {
    List<GeneratedFile> again;
    try {
      List<TypeElement> functionClasses = functionClasses();
      again = compile(new Diagnostics(), functionClasses);
      again.addAll(functionIndex(functionClasses));
    } catch (IOException | RuntimeException e) {
      return; // the build already has its output; this is only a check
    }
    List<GeneratedFile> changed = new ArrayList<>(again);
    changed.removeAll(written);
    if (!changed.isEmpty()) {
      processingEnv.getMessager().printMessage(
          Kind.ERROR,
          "Tessera: " + changed.get(0).name() + " was generated before another annotation processor"
              + " (Lombok?) finished adding members to the classes its templates read; list that"
              + " processor before Tessera in annotationProcessorPaths");
    }
  }

  /** The error for a missing or invalid processor option, or null. */
  private static String optionError(ProcessorOptions options) {
    if (options.templates() == null) {
      return "Tessera: the annotation processor needs -A"
          + ProcessorOptions.TEMPLATES
          + "=<absolute templates dir>, e.g. ${project.basedir}/src/main/resources/templates";
    }
    if (!Files.isDirectory(options.templates())) {
      return "Tessera: templates directory does not exist: " + options.templates();
    }
    for (Path base : options.messages()) {
      String problem = MessageBundle.problem(base);
      if (problem != null) {
        return "Tessera: " + problem + " (-A" + ProcessorOptions.MESSAGES
            + " is a comma-separated list of bundle base paths without .properties)";
      }
    }
    return null;
  }

  /** Remembers this round's {@code @TemplateFunctions} classes by name (elements are per round). */
  private void collectFunctionClasses(RoundEnvironment round) {
    TypeElement annotation = processingEnv.getElementUtils().getTypeElement(FUNCTIONS_ANNOTATION);
    if (annotation != null) {
      for (Element e : round.getElementsAnnotatedWith(annotation)) {
        if (e instanceof TypeElement) {
          functionClassNames.add(((TypeElement) e).getQualifiedName().toString());
        }
      }
    }
  }

  /** The {@code @TemplateFunctions} classes of all rounds so far, resolved in the current round. */
  private List<TypeElement> functionClasses() {
    List<TypeElement> out = new ArrayList<>();
    for (String name : functionClassNames) {
      TypeElement te = processingEnv.getElementUtils().getTypeElement(name);
      if (te != null) {
        out.add(te);
      }
    }
    return out;
  }

  /** One index resource per {@code @TemplateFunctions} class compiled now (invalid namespaces are errors elsewhere). */
  private static List<GeneratedFile> functionIndex(List<TypeElement> classes) {
    Map<String, GeneratedFile> out = new LinkedHashMap<>();
    for (TypeElement te : classes) {
      String ns = namespaceOf(te);
      if (ns != null && SourceVersion.isIdentifier(ns) && !SourceVersion.isKeyword(ns)) {
        // Duplicates are reported by the function registry when templates use functions.
        out.putIfAbsent(ns, new GeneratedFile(GeneratedFile.Kind.RESOURCE, FUNCTION_INDEX + ns, te.getQualifiedName() + "\n"));
      }
    }
    return new ArrayList<>(out.values());
  }

  private static String namespaceOf(TypeElement te) {
    for (AnnotationMirror am : te.getAnnotationMirrors()) {
      if (((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().contentEquals(FUNCTIONS_ANNOTATION)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : am.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value") && e.getValue().getValue() instanceof String) {
            return (String) e.getValue().getValue();
          }
        }
      }
    }
    return null;
  }

  /** The class an earlier compilation indexed for {@code ns}, read from the classpath, or null. */
  private String indexedFunctionClass(String ns) {
    if (!SourceVersion.isIdentifier(ns) || SourceVersion.isKeyword(ns)) {
      return null;
    }
    try {
      FileObject fo = processingEnv.getFiler().getResource(StandardLocation.CLASS_PATH, "", FUNCTION_INDEX + ns);
      String name = fo.getCharContent(true).toString().trim();
      return name.isEmpty() ? null : name;
    } catch (IOException | IllegalArgumentException e) {
      return null; // not indexed
    }
  }

  private void write(List<GeneratedFile> files) throws IOException {
    for (GeneratedFile f : files) {
      FileObject fo =
          f.kind() == GeneratedFile.Kind.JAVA
              ? processingEnv.getFiler().createSourceFile(f.name())
              : processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", f.name());
      try (Writer w = fo.openWriter()) {
        w.write(f.content());
      }
    }
  }

  private static Kind kind(Diagnostic.Severity s) {
    return s == Diagnostic.Severity.ERROR ? Kind.ERROR : Kind.WARNING;
  }

  private static String describe(Throwable e) {
    StringBuilder sb = new StringBuilder(e.toString());
    StackTraceElement[] st = e.getStackTrace();
    for (int i = 0; i < Math.min(5, st.length); i++) {
      sb.append("\n\tat ").append(st[i]);
    }
    return sb.toString();
  }
}
