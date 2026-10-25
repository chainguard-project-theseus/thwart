package dev.tessera.compiler.verify;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.TemplateCompiler;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.messages.MessageBundle;
import dev.tessera.runtime.TemplateFunctions;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the verifier over real applications (#367): the migration bench corpus
 * ({@code bench/migration/work/runs}, local only) or any Maven app with Tessera templates. Not a
 * gate: run by hand with {@code -Dtessera.verify.corpus=<dir>[,<dir>...]}, where each dir is an app
 * or holds apps up to two levels down, and {@code -Dtessera.verify.out=<tsv>}. Each app's
 * classpath comes from {@code mvn dependency:build-classpath}; its sources are compiled with
 * {@code -proc:only}, Lombok (if it is on the classpath) and a processor that compiles the
 * templates as {@code TesseraProcessor} does, then times the verifier on each template.
 */
@EnabledIfSystemProperty(named = "tessera.verify.corpus", matches = ".+")
@DisabledIfSystemProperty(named = "tessera.test.release", matches = "8")
class CorpusRunTest {

  @Test
  void corpus() throws Exception {
    List<Path> apps = new ArrayList<>();
    for (String d : System.getProperty("tessera.verify.corpus").split(",")) {
      apps.addAll(apps(Path.of(d)));
    }
    Path out = Path.of(System.getProperty("tessera.verify.out", "/tmp/tessera-verify-corpus.tsv"));
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      w.println("app\tview\tprofile\tverifier\tms\tmessage");
      for (Path app : apps) {
        run(app, w);
        w.flush();
      }
    }
  }

  private static List<Path> apps(Path dir) throws IOException {
    if (Files.isDirectory(dir.resolve("src/main/resources/templates")) && Files.exists(dir.resolve("pom.xml"))) {
      return List.of(dir);
    }
    List<Path> out = new ArrayList<>();
    try (Stream<Path> s = Files.walk(dir, 3)) {
      for (Path p : s.sorted().collect(Collectors.toList())) {
        if (!p.equals(dir) && !p.toString().contains("/.") && !p.toString().contains("/target/") && Files.isDirectory(p.resolve("src/main/resources/templates")) && Files.exists(p.resolve("pom.xml"))) {
          out.add(p);
        }
      }
    }
    return out;
  }

  private static void run(Path app, PrintWriter w) throws Exception {
    String name = app.getParent().getFileName() + "/" + app.getFileName();
    if (app.getParent().getFileName().toString().startsWith("run-")) {
      name = app.getParent().getParent().getFileName() + "/" + name;
    }
    String pom = Files.readString(app.resolve("pom.xml"));
    Map<String, String> options = new LinkedHashMap<>();
    Matcher m = Pattern.compile("-A(tessera\\.\\w+)=([^<\\s]+)").matcher(pom);
    while (m.find()) {
      options.put(m.group(1), m.group(2).replace("${project.basedir}", app.toString()).replace("${basedir}", app.toString()));
    }
    options.putIfAbsent("tessera.templates", app.resolve("src/main/resources/templates").toString());
    List<String> cp = new ArrayList<>();
    String appCp;
    try {
      appCp = classpath(app);
    } catch (IOException e) {
      w.println(name + "\t#skip\t\t\t\t" + e.getMessage());
      return;
    }
    for (String e : appCp.split(File.pathSeparator)) {
      if (!e.contains("/dev/tessera/") && !e.isEmpty()) {
        cp.add(e);
      }
    }
    cp.addAll(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
    List<Path> sources;
    try (Stream<Path> s = Files.walk(app.resolve("src/main/java"))) {
      sources = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
    StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8);
    List<String> args = new ArrayList<>(List.of("-proc:only", "-classpath", String.join(File.pathSeparator, cp), "-nowarn"));
    options.forEach((k, v) -> args.add("-A" + k + "=" + v));
    JavaCompiler.CompilationTask task = javac.getTask(null, fm, diags, args, null, fm.getJavaFileObjectsFromPaths(sources));
    List<Processor> processors = new ArrayList<>();
    if (cp.stream().anyMatch(e -> e.contains("/org/projectlombok/lombok/"))) {
      for (Processor p : ServiceLoader.load(Processor.class)) {
        if (p.getClass().getName().startsWith("lombok.")) {
          processors.add(p);
        }
      }
    }
    Verify verify = new Verify(name, options, w);
    processors.add(verify);
    task.setProcessors(processors);
    long t0 = System.nanoTime();
    task.call();
    long total = (System.nanoTime() - t0) / 1_000_000;
    w.println(name + "\t#app\t" + verify.profileErrors + "\t" + verify.rejected + "/" + verify.templates + "\t"
        + verify.verifyMillis + "\tjavac+processor " + total + " ms, compile " + verify.compileMillis + " ms");
  }

  private static String classpath(Path app) throws Exception {
    Path cache = Path.of(System.getProperty("java.io.tmpdir"), "tessera-verify-cp",
        app.toString().replace('/', '_') + ".txt");
    if (!Files.exists(cache)) {
      Files.createDirectories(cache.getParent());
      Process p = new ProcessBuilder("mvn", "-q", "-B", "dependency:build-classpath", "-Dmdep.outputFile=" + cache)
          .directory(app.toFile())
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
      if (p.waitFor() != 0 || !Files.exists(cache)) {
        throw new IOException("mvn dependency:build-classpath failed in " + app);
      }
    }
    return Files.readString(cache).trim();
  }

  /** Compiles the templates as {@code TesseraProcessor} does, then verifies each, timed. */
  private static final class Verify extends AbstractProcessor {
    private final String name;
    private final Map<String, String> options;
    private final PrintWriter w;
    private final List<TypeElement> functions = new ArrayList<>();
    private boolean done;
    int templates;
    int rejected;
    int profileErrors;
    long verifyMillis;
    long compileMillis;

    Verify(String name, Map<String, String> options, PrintWriter w) {
      this.name = name;
      this.options = options;
      this.w = w;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Set.of("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
      return Set.of("tessera.templates", "tessera.package", "tessera.messages");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
      TypeElement fa = processingEnv.getElementUtils().getTypeElement(TemplateFunctions.class.getName());
      if (fa != null) {
        for (Element e : round.getElementsAnnotatedWith(fa)) {
          functions.add((TypeElement) e);
        }
      }
      if (done) {
        return false;
      }
      done = true;
      try {
        Path root = Path.of(options.get("tessera.templates"));
        List<TemplateSource> sources = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
          for (Path f : s.filter(p -> p.toString().endsWith(".html")).sorted().collect(Collectors.toList())) {
            String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
            sources.add(new TemplateSource(rel.substring(0, rel.length() - 5), rel, Files.readString(f)));
          }
        }
        List<Path> bundles = new ArrayList<>();
        if (options.containsKey("tessera.messages")) {
          for (String b : options.get("tessera.messages").split(",")) {
            bundles.add(Path.of(b.trim()));
          }
        }
        MessageBundle messages = bundles.isEmpty() ? MessageBundle.unchecked() : MessageBundle.load(bundles);
        Diagnostics d = new Diagnostics();
        CompilerEnv env = new CompilerEnv(processingEnv.getElementUtils(), processingEnv.getTypeUtils(), d,
            options.getOrDefault("tessera.package", CompilerEnv.DEFAULT_PACKAGE), messages, functions);
        long t0 = System.nanoTime();
        Map<String, Skeleton.TemplateSkeleton> skeletons = new TemplateCompiler(env).run(sources).skeletons();
        compileMillis = (System.nanoTime() - t0) / 1_000_000;
        Map<String, String> profile = new LinkedHashMap<>();
        for (Diagnostic x : d.all()) {
          if (x.severity() == Diagnostic.Severity.ERROR && x.pos() != null) {
            profileErrors++;
            profile.putIfAbsent(x.pos().file(), x.message());
          }
        }
        ContextVerifier v = new ContextVerifier(skeletons);
        for (Skeleton.TemplateSkeleton t : skeletons.values()) {
          String file = t.viewName() + ".html";
          long s0 = System.nanoTime();
          List<Finding> found = v.check(t);
          long ms = (System.nanoTime() - s0) / 1_000_000;
          verifyMillis += ms;
          templates++;
          if (!found.isEmpty()) {
            rejected++;
          }
          String p = profile.get(file);
          w.println(name + "\t" + t.viewName() + "\t" + (p == null ? "ok" : "ERROR " + oneLine(p)) + "\t"
              + (found.isEmpty() ? "ACCEPT" : "REJECT") + "\t" + ms + "\t"
              + (found.isEmpty() ? "" : found.size() + " " + found.get(0).at + ": " + oneLine(VerifierMessages.message(found.get(0)))));
          if (Boolean.getBoolean("tessera.verify.all")) {
            // Every finding, for counting false positives per rule and call site (#329).
            for (Finding f : found) {
              w.println(name + "\t#finding\t" + t.viewName() + "\t" + f.problem + "\t" + f.at + "\t"
                  + (f.inner == null ? "" : f.fragment + " @ " + f.inner) + "\t" + oneLine(VerifierMessages.message(f)));
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        w.println(name + "\t#crash\t\t\t\t" + oneLine(e.toString()));
      }
      return false;
    }

    private static String oneLine(String s) {
      return s.replace('\n', ' ').replace('\t', ' ');
    }
  }
}
