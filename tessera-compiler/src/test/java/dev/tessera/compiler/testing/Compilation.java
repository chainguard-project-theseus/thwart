package dev.tessera.compiler.testing;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.TemplateCompiler;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.messages.MessageBundle;
import dev.tessera.compiler.processor.ProcessorOptions;
import dev.tessera.compiler.processor.TesseraProcessor;
import dev.tessera.compiler.verify.ContextVerifier;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.compiler.verify.SkeletonMatcher;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * In-memory end-to-end compile harness: writes templates to a temp templates root, compiles
 * Java sources with javac and the {@link TesseraProcessor}, and lets tests render the result
 * or assert on diagnostics. Every compiler test should go through this.
 *
 * <pre>{@code
 * Compilation c = Compilation.builder()
 *     .java("com.example.User", "package com.example; public record User(String name) {}")
 *     .template("hello.html", "<p t:params=\"com.example.User user\" t:text=\"user.name\">x</p>")
 *     .compile()
 *     .assertSuccess();
 * assertEquals("<p>Ann</p>", c.render("hello", Map.of("user", c.newInstance(...))));
 * c = Compilation.builder().template("bad.html", "...").compile();
 * c.assertError("bad.html:1:4", "not view-safe");
 * }</pre>
 */
public final class Compilation {
  private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
  private final boolean success;
  private final MemoryFileManager files;
  private final String basePackage;
  private final ClassLoader loader;
  private final Map<String, Skeleton.TemplateSkeleton> skeletons;
  private final Compilation upstream;

  private Compilation(
      List<Diagnostic<? extends JavaFileObject>> diagnostics,
      boolean success,
      MemoryFileManager files,
      String basePackage,
      Map<String, Skeleton.TemplateSkeleton> skeletons,
      Compilation upstream) {
    this.diagnostics = diagnostics;
    this.success = success;
    this.files = files;
    this.basePackage = basePackage;
    this.skeletons = skeletons;
    this.upstream = upstream;
    this.loader =
        new MemoryClassLoader(files, upstream == null ? Compilation.class.getClassLoader() : upstream.loader);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link CompilerEnv} inside a processor callback, for stage-level unit tests. */
  public static CompilerEnv env(ProcessingEnvironment pe, DiagnosticSink sink) {
    return env(pe, sink, MessageBundle.unchecked(), List.of());
  }

  /** As {@link #env(ProcessingEnvironment, DiagnosticSink)}, with a message bundle and function namespaces. */
  public static CompilerEnv env(
      ProcessingEnvironment pe, DiagnosticSink sink, MessageBundle messages, List<TypeElement> functionNamespaces) {
    return new CompilerEnv(
        pe.getElementUtils(), pe.getTypeUtils(), sink, CompilerEnv.DEFAULT_PACKAGE, messages, functionNamespaces);
  }

  /**
   * Compiles {@code package tessera.eval; public final class Eval { public static Object run() {
   * <body> } }} with {@code sources}, then loads and invokes {@code run()} (test code only).
   */
  public static Object evalRun(Map<String, String> sources, String body) {
    String src = "package tessera.eval; public final class Eval { public static Object run() {\n" + body + " } }";
    Builder b = builder();
    sources.forEach(b::java);
    Compilation c = b.java("tessera.eval.Eval", src).compile();
    if (!c.success()) {
      throw new AssertionError("generated code does not compile:\n" + src + "\n" + c.errors());
    }
    try {
      return c.classLoader().loadClass("tessera.eval.Eval").getMethod("run").invoke(null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** A render model from alternating keys and values; insertion-ordered, null values allowed. */
  public static Map<String, Object> model(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  public static final class Builder {
    private final Map<String, String> templates = new LinkedHashMap<>();
    private final Map<String, String> javaSources = new LinkedHashMap<>();
    private final Map<String, String> options = new LinkedHashMap<>();
    private String messages;
    private final Map<String, String> localeMessages = new LinkedHashMap<>();
    private boolean templatesOption = true;
    private Integer release = Integer.getInteger("tessera.test.release");
    private Consumer<ProcessingEnvironment> callback;
    private Compilation upstream;
    private final List<Processor> before = new ArrayList<>();
    private final List<Processor> after = new ArrayList<>();
    private Map<String, Skeleton.TemplateSkeleton> extraSkeletons = Map.of();
    private boolean verifierOnly;
    private boolean withoutVerifier;

    /** Adds a template at {@code path} relative to the templates root, e.g. {@code owners/list.html}. */
    public Builder template(String path, String content) {
      templates.put(path, content);
      return this;
    }

    /** Adds a Java source file for class {@code qualifiedName}. */
    public Builder java(String qualifiedName, String source) {
      javaSources.put(qualifiedName, source);
      return this;
    }

    /** Default-locale message bundle contents ({@code .properties} syntax); enables key checks. */
    public Builder messages(String properties) {
      this.messages = properties;
      return this;
    }

    /**
     * A locale file beside the default bundle, e.g. {@code localeMessages("fr", "a=b")} writes
     * {@code messages_fr.properties}. Only used together with {@link #messages}.
     */
    public Builder localeMessages(String locale, String properties) {
      localeMessages.put(locale, properties);
      return this;
    }

    /** Sets a javac {@code -A} option (e.g. {@code tessera.package}). */
    public Builder option(String key, String value) {
      options.put(key, value);
      return this;
    }

    /**
     * Compiles with {@code javac --release <release>}, e.g. 8 to check that generated code runs on
     * the Java 8 floor (#154). Below 16, records in {@link #java} sources are rewritten to the
     * equivalent {@code @TemplateView} getter classes ({@link Java8Records}). Default: the
     * {@code tessera.test.release} system property, else the test JVM's own level.
     */
    public Builder release(int release) {
      this.release = release;
      return this;
    }

    /**
     * Puts {@code earlier}'s classes and resources on the classpath (ahead of the test classpath)
     * and its class loader under this one's, like a test compile against the main output (#118).
     */
    public Builder classpath(Compilation earlier) {
      this.upstream = earlier.assertSuccess();
      return this;
    }

    /** Omits {@code -Atessera.templates} (to test the processor's error). */
    public Builder withoutTemplatesOption() {
      this.templatesOption = false;
      return this;
    }

    /**
     * Runs {@code callback} with javac's ProcessingEnvironment during the first round (before
     * Tessera's processor), for unit-testing type resolution against {@link #java} sources. Anything
     * the callback throws is rethrown from {@link #compile()}.
     */
    public Builder inProcessor(Consumer<ProcessingEnvironment> callback) {
      this.callback = callback;
      return this;
    }

    /**
     * Also runs {@code processor} (e.g. Lombok's), in the same rounds as Tessera's and ahead of it
     * or after it in javac's processor order (#233).
     */
    public Builder processor(Processor processor, boolean beforeTessera) {
      (beforeTessera ? before : after).add(processor);
      return this;
    }

    /**
     * Skeletons of templates generated outside Tessera's processor (by an {@link #inProcessor}
     * callback that runs the generator itself); read when the compilation ends, so the callback may
     * fill {@code skeletons} while it runs.
     */
    public Builder skeletons(Map<String, Skeleton.TemplateSkeleton> skeletons) {
      this.extraSkeletons = skeletons;
      return this;
    }

    /**
     * Test-only (#367): reports only the context verifier's errors. The templates are compiled
     * with every other diagnostic dropped, the profile's included, and the verifier runs on the
     * skeletons whatever they reported; nothing is generated. Proves that the verifier rejects a
     * template on its own.
     */
    public Builder verifierOnly() {
      this.verifierOnly = true;
      return this;
    }

    /**
     * Test-only (#324): compiles and generates as usual but drops the context verifier's findings
     * (it still names the escaper of element text, #378), so
     * the oracle gate can render a template the verifier rejects and report it as a false-positive
     * candidate when the value checks find nothing. The profile's errors still fail the build. Never
     * use it to make a template compile.
     */
    public Builder withoutVerifier() {
      this.withoutVerifier = true;
      return this;
    }

    public Compilation compile() {
      Path dir = null;
      try {
        dir = Files.createTempDirectory("tessera-test");
        Path root = Files.createDirectories(dir.resolve("templates"));
        for (Map.Entry<String, String> e : templates.entrySet()) {
          Path p = root.resolve(e.getKey());
          Files.createDirectories(p.getParent());
          Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        List<String> args = new ArrayList<>();
        args.add("-classpath");
        String classpath = System.getProperty("java.class.path");
        if (upstream != null) {
          Path out = dir.resolve("upstream");
          upstream.files.writeTo(out);
          classpath = out + java.io.File.pathSeparator + classpath;
        }
        args.add(classpath);
        args.add("-proc:full");
        if (release != null) {
          args.add("--release");
          args.add(release.toString());
          args.add("-Xlint:-options"); // javac 21+ warns that --release 8 is obsolete
        }
        if (templatesOption) {
          args.add("-A" + ProcessorOptions.TEMPLATES + "=" + root.toAbsolutePath());
        }
        if (messages != null) {
          Path base = dir.resolve("messages");
          Files.writeString(MessageBundle.file(base), messages, StandardCharsets.UTF_8);
          for (Map.Entry<String, String> e : localeMessages.entrySet()) {
            Files.writeString(dir.resolve("messages_" + e.getKey() + ".properties"), e.getValue(), StandardCharsets.UTF_8);
          }
          args.add("-A" + ProcessorOptions.MESSAGES + "=" + base.toAbsolutePath());
        }
        options.forEach((k, v) -> args.add("-A" + k + "=" + v));

        List<JavaFileObject> units = new ArrayList<>();
        boolean noRecords = release != null && release < 16;
        javaSources.forEach(
            (name, src) -> units.add(new SourceFile(name, noRecords ? Java8Records.desugar(src) : src)));
        if (units.isEmpty()) {
          // javac only runs processors when it has something to compile.
          units.add(new SourceFile("tessera.test.Placeholder", "package tessera.test; class Placeholder {}"));
        }

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager std = javac.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
        MemoryFileManager mem = new MemoryFileManager(std);
        List<Processor> processors = new ArrayList<>();
        CallbackProcessor callbackProcessor = callback == null ? null : new CallbackProcessor(callback);
        if (callbackProcessor != null) {
          processors.add(callbackProcessor);
        }
        processors.addAll(before);
        TesseraProcessor tessera = new TesseraProcessor();
        VerifierOnlyProcessor verifier = verifierOnly ? new VerifierOnlyProcessor(templates) : null;
        UnverifiedProcessor unverified = withoutVerifier ? new UnverifiedProcessor(templates) : null;
        processors.add(verifierOnly ? verifier : withoutVerifier ? unverified : tessera);
        processors.addAll(after);
        JavaCompiler.CompilationTask task = javac.getTask(null, mem, collector, args, null, units);
        task.setProcessors(processors);
        boolean ok = task.call();
        dumpGenerated(mem);
        if (callbackProcessor != null && callbackProcessor.failure != null) {
          Throwable t = callbackProcessor.failure;
          if (t instanceof RuntimeException r) {
            throw r;
          }
          if (t instanceof Error e) {
            throw e;
          }
          throw new AssertionError(t);
        }
        return new Compilation(
            collector.getDiagnostics(),
            ok,
            mem,
            options.getOrDefault(ProcessorOptions.PACKAGE, CompilerEnv.DEFAULT_PACKAGE),
            merge(verifierOnly ? verifier.skeletons : withoutVerifier ? unverified.skeletons : tessera.skeletons(),
                extraSkeletons),
            upstream);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } finally {
        if (dir != null) {
          deleteRecursively(dir);
        }
      }
    }

    private static Map<String, Skeleton.TemplateSkeleton> merge(
        Map<String, Skeleton.TemplateSkeleton> a, Map<String, Skeleton.TemplateSkeleton> b) {
      Map<String, Skeleton.TemplateSkeleton> all = new LinkedHashMap<>(a);
      all.putAll(b);
      return all;
    }

    /**
     * With {@code -Dtessera.test.dumpGenerated=<dir>}, writes every generated source under
     * {@code <dir>/<hash of this compilation's inputs>/}, so the generated code of a whole test run
     * can be compared before and after a codegen change (#366).
     */
    private void dumpGenerated(MemoryFileManager mem) throws IOException {
      String dump = System.getProperty("tessera.test.dumpGenerated");
      if (dump == null) {
        return;
      }
      String inputs = List.of(templates, javaSources, options, String.valueOf(messages), localeMessages,
          String.valueOf(release), templatesOption).toString();
      String key;
      try {
        key = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(inputs.getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new AssertionError(e);
      }
      Path out = Files.createDirectories(Path.of(dump, key));
      for (Map.Entry<String, MemoryFile> e : mem.sources.entrySet()) {
        Files.writeString(out.resolve(e.getKey() + ".java"), e.getValue().getCharContent(true), StandardCharsets.UTF_8);
      }
    }
  }

  public boolean success() {
    return success;
  }

  /** All diagnostics as {@code KIND: message} strings. */
  public List<String> diagnostics() {
    return diagnostics.stream().map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT)).toList();
  }

  /** Error messages only. Tessera errors look like {@code path.html:line:col: error: message}. */
  public List<String> errors() {
    return diagnostics.stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .map(d -> d.getMessage(Locale.ROOT))
        .toList();
  }

  public Compilation assertSuccess() {
    if (!success) {
      throw new AssertionError("compilation failed:\n" + String.join("\n", diagnostics()));
    }
    return this;
  }

  /** Asserts that compilation failed with an error containing every one of {@code fragments}. */
  public Compilation assertError(String... fragments) {
    if (success) {
      throw new AssertionError("expected compilation to fail, but it succeeded");
    }
    boolean found =
        errors().stream().anyMatch(e -> Stream.of(fragments).allMatch(e::contains));
    if (!found) {
      throw new AssertionError(
          "no error containing " + List.of(fragments) + "; errors:\n" + String.join("\n", errors()));
    }
    return this;
  }

  /** Generated Java source for {@code qualifiedName}, or null. */
  public String generatedSource(String qualifiedName) {
    return files.generatedSource(qualifiedName);
  }

  /** Class file bytes javac wrote for {@code qualifiedName} (generated or test source), or null. */
  public byte[] classFile(String qualifiedName) {
    return files.classBytes(qualifiedName);
  }

  /** Generated resource (e.g. the services file), or null. */
  public String generatedResource(String path) {
    return files.resource(path);
  }

  /** Loads compiled classes (generated templates and test sources). */
  public ClassLoader classLoader() {
    return loader;
  }

  /** The generated {@code TesseraRegistry} (instantiated reflectively; test code only). */
  public TemplateRegistry registry() {
    assertSuccess();
    try {
      Class<?> c = loader.loadClass(basePackage + ".TesseraRegistry");
      return (TemplateRegistry) c.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("cannot load generated registry", e);
    }
  }

  public String render(String viewName, Map<String, ?> model) {
    return render(viewName, model, RenderContext.defaults());
  }

  /**
   * Renders {@code viewName}, and checks that the output is in the language of the template's
   * skeleton (#366): every render in the test suite proves that the skeleton the verifier checks is
   * what the generated class writes, and that each hole's output stays in its escaper's alphabet.
   */
  public String render(String viewName, Map<String, ?> model, RenderContext ctx) {
    TemplateEntry entry =
        registry().find(viewName).orElseThrow(() -> new AssertionError("no template " + viewName));
    StringOutput out = new StringOutput();
    entry.renderer().render(model, ctx, out);
    String output = out.toString();
    int sep = viewName.indexOf(" :: ");
    if (sep < 0) {
      assertInSkeleton(viewName, output);
    } else {
      // A fragment entry (#131): the output is in the fragment's skeleton.
      Skeleton.FragmentId id = new Skeleton.FragmentId(viewName.substring(0, sep), viewName.substring(sep + 4));
      SkeletonMatcher.Result r = SkeletonMatcher.match(fragmentSkeleton(id), output, this::fragmentSkeleton);
      if (!r.matches()) {
        throw new AssertionError("render of " + viewName + " is not in its skeleton at offset " + r.offset()
            + "\nexpected: " + r.expected() + "\noutput: " + output);
      }
    }
    return output;
  }

  /**
   * The skeleton of template {@code viewName} compiled here or upstream ({@link Builder#classpath}),
   * or null.
   */
  public Skeleton.TemplateSkeleton skeleton(String viewName) {
    for (Compilation c = this; c != null; c = c.upstream) {
      Skeleton.TemplateSkeleton s = c.skeletons.get(viewName);
      if (s != null) {
        return s;
      }
    }
    return null;
  }

  /** The skeleton of fragment {@code id}, or null. */
  public Skeleton.Seq fragmentSkeleton(Skeleton.FragmentId id) {
    Skeleton.TemplateSkeleton t = skeleton(id.view());
    return t == null ? null : t.fragments().get(id.name());
  }

  /**
   * Fails unless {@code output} is in the language of {@code viewName}'s skeleton, naming the first
   * offset no path through the skeleton gets past and the node expected there.
   */
  public void assertInSkeleton(String viewName, String output) {
    Skeleton.TemplateSkeleton t = skeleton(viewName);
    if (t == null) {
      throw new AssertionError("no skeleton for template " + viewName);
    }
    SkeletonMatcher.Result r = SkeletonMatcher.match(t.body(), output, this::fragmentSkeleton);
    SkeletonFidelity.record(this, viewName, t, r.matches());
    if (!r.matches()) {
      int from = Math.max(0, r.offset() - 40);
      throw new AssertionError(
          "render of " + viewName + " is not in its skeleton at offset " + r.offset() + ": ..."
              + output.substring(from, r.offset()) + "\u2038" + output.substring(r.offset(), Math.min(output.length(), r.offset() + 40))
              + "...\nexpected: " + r.expected() + "\nskeleton: " + t + "\noutput: " + output);
    }
  }

  /** Creates an instance of a compiled test class via its public constructor (test code only). */
  public Object newInstance(String qualifiedName, Object... args) {
    try {
      Class<?> c = loader.loadClass(qualifiedName);
      for (var ctor : c.getConstructors()) {
        if (ctor.getParameterCount() == args.length) {
          return ctor.newInstance(args);
        }
      }
      throw new AssertionError("no public constructor with " + args.length + " args on " + qualifiedName);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** The constant {@code name} of compiled test enum {@code qualifiedName} (test code only). */
  public Object enumConstant(String qualifiedName, String name) {
    try {
      Class<?> cls = loader.loadClass(qualifiedName);
      for (Object o : cls.getEnumConstants()) {
        if (((Enum<?>) o).name().equals(name)) {
          return o;
        }
      }
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("no constant " + name + " on " + qualifiedName);
  }

  private static void deleteRecursively(Path dir) {
    try (Stream<Path> s = Files.walk(dir)) {
      s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // best effort
    }
  }

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    SourceFile(String qualifiedName, String source) {
      super(URI.create("mem:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  /** A file javac writes (class, generated source or resource) and may read back. */
  private static final class MemoryFile extends SimpleJavaFileObject {
    private byte[] bytes = new byte[0];

    MemoryFile(URI uri, Kind kind) {
      super(uri, kind);
    }

    @Override
    public OutputStream openOutputStream() {
      return new ByteArrayOutputStream() {
        @Override
        public void close() throws IOException {
          super.close();
          bytes = toByteArray();
        }
      };
    }

    @Override
    public java.io.InputStream openInputStream() {
      return new java.io.ByteArrayInputStream(bytes);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, MemoryFile> classes = new LinkedHashMap<>();
    private final Map<String, MemoryFile> sources = new LinkedHashMap<>();
    private final Map<String, MemoryFile> resources = new LinkedHashMap<>();

    MemoryFileManager(StandardJavaFileManager delegate) {
      super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        JavaFileManager.Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
      String path = className.replace('.', '/') + kind.extension;
      MemoryFile f = new MemoryFile(URI.create("mem:///" + location.getName() + "/" + path), kind);
      if (kind == JavaFileObject.Kind.CLASS) {
        classes.put(className, f);
      } else {
        sources.put(className, f);
      }
      return f;
    }

    @Override
    public FileObject getFileForOutput(
        JavaFileManager.Location location, String packageName, String relativeName, FileObject sibling) {
      String path = packageName.isEmpty() ? relativeName : packageName.replace('.', '/') + "/" + relativeName;
      MemoryFile f = new MemoryFile(URI.create("mem:///" + location.getName() + "/" + path), JavaFileObject.Kind.OTHER);
      if (location == StandardLocation.CLASS_OUTPUT) {
        resources.put(path, f);
      }
      return f;
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
      return a.toUri().equals(b.toUri());
    }

    /** Writes the class files and class-output resources under {@code root}, as javac {@code -d} would. */
    void writeTo(Path root) throws IOException {
      for (Map.Entry<String, MemoryFile> e : classes.entrySet()) {
        write(root.resolve(e.getKey().replace('.', '/') + ".class"), e.getValue().bytes);
      }
      for (Map.Entry<String, MemoryFile> e : resources.entrySet()) {
        write(root.resolve(e.getKey()), e.getValue().bytes);
      }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
    }

    byte[] classBytes(String name) {
      MemoryFile f = classes.get(name);
      return f == null ? null : f.bytes;
    }

    String generatedSource(String name) {
      MemoryFile f = sources.get(name);
      return f == null ? null : f.getCharContent(true).toString();
    }

    String resource(String path) {
      MemoryFile f = resources.get(path);
      return f == null ? null : f.getCharContent(true).toString();
    }
  }

  private static final class MemoryClassLoader extends ClassLoader {
    private final MemoryFileManager files;

    MemoryClassLoader(MemoryFileManager files, ClassLoader parent) {
      super(parent);
      this.files = files;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] b = files.classBytes(name);
      if (b == null) {
        throw new ClassNotFoundException(name);
      }
      return defineClass(name, b, 0, b.length);
    }
  }

  /** {@link Builder#verifierOnly()}: the compiler with its diagnostics dropped, then the verifier. */
  private static final class VerifierOnlyProcessor extends AbstractProcessor {
    private final Map<String, String> templates;
    private boolean done;
    Map<String, Skeleton.TemplateSkeleton> skeletons = Map.of();

    VerifierOnlyProcessor(Map<String, String> templates) {
      this.templates = templates;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (done) {
        return false;
      }
      done = true;
      List<TemplateSource> sources = new ArrayList<>();
      templates.forEach((path, content) -> sources.add(
          new TemplateSource(path.substring(0, path.length() - ".html".length()), path, content)));
      try {
        // The message bundle, if any, so HTML messages are recorded as their markup (#329).
        String bundles = processingEnv.getOptions().get("tessera.messages");
        List<Path> paths = new ArrayList<>();
        if (bundles != null) {
          for (String b : bundles.split(",")) {
            paths.add(Path.of(b.trim()));
          }
        }
        MessageBundle messages = paths.isEmpty() ? MessageBundle.unchecked() : MessageBundle.load(paths);
        skeletons = new TemplateCompiler(env(processingEnv, d -> {}, messages, List.of())).run(sources).skeletons();
      } catch (RuntimeException e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "compiler failed without its checks: " + e);
        return false;
      }
      ContextVerifier.verify(skeletons, d -> processingEnv.getMessager().printMessage(
          d.severity() == dev.tessera.compiler.Diagnostic.Severity.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
          d.toString()));
      return false;
    }
  }

  /**
   * {@link Builder#withoutVerifier()}: the compiler with its diagnostics reported and its files
   * written when it has no errors, without the context verifier's findings. No Lombok rounds, no
   * function index.
   */
  private static final class UnverifiedProcessor extends AbstractProcessor {
    private final Map<String, String> templates;
    private boolean done;
    Map<String, Skeleton.TemplateSkeleton> skeletons = Map.of();

    UnverifiedProcessor(Map<String, String> templates) {
      this.templates = templates;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (done) {
        return false;
      }
      done = true;
      List<TemplateSource> sources = new ArrayList<>();
      templates.forEach((path, content) -> sources.add(
          new TemplateSource(path.substring(0, path.length() - ".html".length()), path, content)));
      List<dev.tessera.compiler.Diagnostic> errors = new ArrayList<>();
      TemplateCompiler.Result result;
      try {
        result = new TemplateCompiler(env(processingEnv, d -> {
          processingEnv.getMessager().printMessage(
              d.severity() == dev.tessera.compiler.Diagnostic.Severity.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
              d.toString());
          if (d.severity() == dev.tessera.compiler.Diagnostic.Severity.ERROR) {
            errors.add(d);
          }
        })).run(sources);
      } catch (RuntimeException e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Tessera: internal compiler error: " + e);
        return false;
      }
      if (!errors.isEmpty()) {
        return false;
      }
      try {
        for (dev.tessera.compiler.GeneratedFile f : result.files(unverifiedEscapers(result.skeletons()))) {
          FileObject fo = f.kind() == dev.tessera.compiler.GeneratedFile.Kind.JAVA
              ? processingEnv.getFiler().createSourceFile(f.name())
              : processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", f.name());
          try (java.io.Writer w = fo.openWriter()) {
            w.write(f.content());
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      skeletons = result.skeletons();
      return false;
    }
  }

  /**
   * Test-only (#378): the escaper of each element-text hole in {@code skeletons}, for code written
   * without the verifier's findings. The verifier names it where it reaches the hole; in a template
   * it rejects it may not reach every hole, and those take the escaper the host element's name
   * implies ({@code rcdata} in a title or textarea), as the compiler chose before #378.
   */
  public static Map<Skeleton.Hole, Skeleton.Escaper> unverifiedEscapers(Map<String, Skeleton.TemplateSkeleton> skeletons) {
    Map<Skeleton.Hole, Skeleton.Escaper> escapers = new IdentityHashMap<>(ContextVerifier.verify(skeletons, d -> {}));
    for (Skeleton.TemplateSkeleton t : skeletons.values()) {
      textHoles(t.body(), escapers);
      t.fragments().values().forEach(f -> textHoles(f, escapers));
    }
    return escapers;
  }

  private static void textHoles(Skeleton.Node n, Map<Skeleton.Hole, Skeleton.Escaper> escapers) {
    switch (n) {
      case Skeleton.Hole h when h.escaper() == Skeleton.Escaper.TEXT -> escapers.putIfAbsent(
          h, Set.of("title", "textarea").contains(h.element()) ? Skeleton.Escaper.RCDATA : Skeleton.Escaper.TEXT);
      case Skeleton.Seq s -> s.nodes().forEach(c -> textHoles(c, escapers));
      case Skeleton.Opt o -> textHoles(o.body(), escapers);
      case Skeleton.Alt a -> a.branches().forEach(b -> textHoles(b, escapers));
      case Skeleton.Rep r -> {
        textHoles(r.body(), escapers);
        textHoles(r.separator(), escapers);
      }
      case Skeleton.Call c -> c.fragmentArgs().values().forEach(b -> textHoles(b, escapers));
      default -> { }
    }
  }

  private static final class CallbackProcessor extends AbstractProcessor {
    private final Consumer<ProcessingEnvironment> callback;
    private boolean done;
    private Throwable failure;

    CallbackProcessor(Consumer<ProcessingEnvironment> callback) {
      this.callback = callback;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!done) {
        done = true;
        try {
          callback.accept(processingEnv);
        } catch (Throwable t) {
          failure = t;
        }
      }
      return false;
    }
  }
}
