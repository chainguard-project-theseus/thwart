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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * javac entry point. Runs once, in the first round: discovers templates under
 * {@code -Atessera.templates}, compiles them with {@link TemplateCompiler}, writes the generated
 * sources and resources through the Filer, and reports diagnostics as
 * {@code file:line:col: message} javac errors. If any template has an error, nothing is written.
 * Never claims annotations, so other processors are unaffected.
 */
public final class TesseraProcessor extends AbstractProcessor {
  private static final String FUNCTIONS_ANNOTATION = TemplateFunctions.class.getName();

  private boolean done;

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
      return false;
    }
    done = true;
    Messager messager = processingEnv.getMessager();
    ProcessorOptions options = ProcessorOptions.parse(processingEnv.getOptions());
    if (options.templates() == null) {
      messager.printMessage(
          Kind.ERROR,
          "Tessera: the annotation processor needs -A"
              + ProcessorOptions.TEMPLATES
              + "=<absolute templates dir>, e.g. ${project.basedir}/src/main/resources/templates");
      return false;
    }
    if (!Files.isDirectory(options.templates())) {
      messager.printMessage(
          Kind.ERROR, "Tessera: templates directory does not exist: " + options.templates());
      return false;
    }
    if (options.messages() != null && !Files.isRegularFile(MessageBundle.file(options.messages()))) {
      messager.printMessage(
          Kind.ERROR,
          "Tessera: message bundle does not exist: " + MessageBundle.file(options.messages())
              + " (-A" + ProcessorOptions.MESSAGES + " is the bundle base path without .properties)");
      return false;
    }
    try {
      List<TemplateSource> sources = TemplateDiscovery.discover(options.templates());
      Diagnostics diagnostics = new Diagnostics();
      MessageBundle messages =
          options.messages() == null ? MessageBundle.unchecked() : MessageBundle.load(options.messages());
      CompilerEnv env =
          new CompilerEnv(
              processingEnv.getElementUtils(),
              processingEnv.getTypeUtils(),
              diagnostics,
              options.basePackage(),
              messages,
              functionNamespaces(round));
      List<GeneratedFile> files = new TemplateCompiler(env).compile(sources);
      for (Diagnostic d : diagnostics.all()) {
        messager.printMessage(kind(d.severity()), d.toString());
      }
      // The only place that enforces "nothing is written on error".
      if (!diagnostics.hasErrors()) {
        write(files);
      }
    } catch (IOException | RuntimeException e) {
      messager.printMessage(Kind.ERROR, "Tessera: internal compiler error: " + describe(e));
    }
    return false;
  }

  private List<TypeElement> functionNamespaces(RoundEnvironment round) {
    TypeElement annotation = processingEnv.getElementUtils().getTypeElement(FUNCTIONS_ANNOTATION);
    List<TypeElement> out = new ArrayList<>();
    if (annotation != null) {
      for (Element e : round.getElementsAnnotatedWith(annotation)) {
        if (e instanceof TypeElement) {
          out.add((TypeElement) e);
        }
      }
    }
    return out;
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
