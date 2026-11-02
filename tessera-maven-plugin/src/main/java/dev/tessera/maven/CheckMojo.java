package dev.tessera.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Makes {@code compile} run the Tessera annotation processor again when templates or message
 * bundles changed.
 *
 * <p>maven-compiler-plugin runs javac only when Java sources, the source set or the classpath
 * change, so a template-only or message-only edit used to leave the old generated classes and a
 * green build. The processor records the templates and message files it read ({@link
 * TemplateFingerprint#RESOURCE} in the class output). Before {@code compile}, this goal compares
 * that record with the templates directory and the {@code messages} basenames. If a file was added,
 * edited or deleted, or the class output has classes but no record, it deletes the compile output
 * (the class files and Tessera's resources in the main and test class directories, the processors'
 * generated sources and the compiler plugin's incremental state), so the compiler plugin compiles
 * the module from scratch whatever its version or incremental mode. A first build (no classes
 * yet) is left alone. Other files in the class directories are kept: copied resources, and files
 * a plugin wrote earlier in the build, such as git-commit-id's {@code git.properties}.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class CheckMojo extends AbstractMojo {
  static final String OTHER_MESSAGES = "the last compile read other message bundles";

  /** The processor's resources in a class output directory, besides the class files. */
  static final String PROCESSOR_RESOURCES = "META-INF/tessera/";
  static final String SERVICES_FILE = "META-INF/services/dev.tessera.runtime.TemplateRegistry";

  /** The templates root: the same directory as {@code -Atessera.templates}. */
  @Parameter(defaultValue = "${project.basedir}/src/main/resources/templates", required = true)
  File templates;

  /**
   * The same value as {@code -Atessera.messages}: comma-separated bundle basenames without {@code
   * .properties}. Empty when the build does not set that option.
   */
  @Parameter(property = "tessera.messages")
  String messages;

  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  File basedir;

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
  File outputDirectory;

  @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
  File testOutputDirectory;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
  File buildDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      byte[] current = templates.isDirectory() ? TemplateFingerprint.of(templates.toPath(), messagePaths()) : null;
      Stale stale = stale(outputDirectory.toPath(), current, true);
      if (stale == null) {
        // The processor also runs in test compilation, which writes its own record.
        stale = stale(testOutputDirectory.toPath(), current, false);
      }
      if (stale == null) {
        getLog().debug("Tessera: templates and messages unchanged since the last compile");
        return;
      }
      if (stale.misconfigured) {
        getLog().warn("Tessera: " + stale.reason);
      } else {
        getLog().info("Tessera: " + stale.reason + "; deleting the compile output so the templates are compiled again");
      }
      Path build = buildDirectory.toPath();
      deleteCompilerOutput(outputDirectory.toPath());
      deleteCompilerOutput(testOutputDirectory.toPath());
      delete(build.resolve("generated-sources/annotations"));
      delete(build.resolve("generated-test-sources/test-annotations"));
      delete(build.resolve("maven-status/maven-compiler-plugin"));
    } catch (IOException e) {
      throw new MojoExecutionException(
          "Tessera: cannot check whether the templates changed since the last compile (" + e
              + "); run mvn clean compile", e);
    }
  }

  /** {@link #messages} parsed like the processor option; relative paths are against the project. */
  List<Path> messagePaths() {
    List<Path> out = new ArrayList<>();
    if (messages != null) {
      for (String base : messages.split(",")) {
        if (!base.trim().isEmpty()) {
          out.add(basedir.toPath().resolve(base.trim()));
        }
      }
    }
    return out;
  }

  /**
   * Why the output in {@code classes} is stale, or null. Main output with class files but without
   * a record is stale (a failed compile, or one from before the record); without class files it is a first build that
   * other plugins may have written to; test output without one is not (its compile may not
   * run the processor).
   */
  static Stale stale(Path classes, byte[] current, boolean main) throws IOException {
    Path recorded = classes.resolve(TemplateFingerprint.RESOURCE);
    if (Files.isRegularFile(recorded)) {
      byte[] last = Files.readAllBytes(recorded);
      if (current != null && Arrays.equals(current, last)) {
        return null;
      }
      String[] was = new String(last, StandardCharsets.UTF_8).split("\n", -1);
      if (!was[0].equals(TemplateFingerprint.VERSION)) {
        return new Stale("the template fingerprint is from another version of Tessera", false);
      }
      if (current != null) {
        List<String> now = bundles(new String(current, StandardCharsets.UTF_8).split("\n", -1));
        if (!now.equals(bundles(was))) {
          // Every build would recompile: the processor and this goal disagree on the bundles.
          return new Stale(OTHER_MESSAGES + " (-Atessera.messages: " + bundles(was) + ") than the tessera-maven-plugin"
              + " messages parameter (" + now + "); set it to the same value, for example with the property"
              + " tessera.messages, or every build recompiles the module", true);
        }
      }
      return new Stale("templates or messages changed since the last compile", false);
    }
    return main && hasClassFiles(classes) ? new Stale("no template fingerprint in " + classes, false) : null;
  }

  /** Why a class output is stale; misconfigured when the build's settings, not an edit, make it so. */
  static final class Stale {
    final String reason;
    final boolean misconfigured;

    Stale(String reason, boolean misconfigured) {
      this.reason = reason;
      this.misconfigured = misconfigured;
    }
  }

  /** The {@code messages <name>} lines of a fingerprint. */
  private static List<String> bundles(String[] lines) {
    List<String> out = new ArrayList<>();
    for (String l : lines) {
      if (l.startsWith("messages ")) {
        out.add(l.substring("messages ".length()));
      }
    }
    return out;
  }

  private static boolean hasClassFiles(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.anyMatch(p -> p.getFileName().toString().endsWith(".class") && Files.isRegularFile(p));
    }
  }

  /** True for what javac and the Tessera processor write into {@code classes}. */
  static boolean isCompilerOutput(Path classes, Path file) {
    String rel = classes.relativize(file).toString().replace(File.separatorChar, '/');
    return rel.endsWith(".class") || rel.startsWith(PROCESSOR_RESOURCES) || rel.equals(SERVICES_FILE);
  }

  /**
   * Deletes the compiler's and processor's files in a class output directory, and the directories
   * that leaves empty; keeps every other file.
   */
  static void deleteCompilerOutput(Path classes) throws IOException {
    if (!Files.isDirectory(classes)) {
      return;
    }
    Files.walkFileTree(classes, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (isCompilerOutput(classes, file)) {
          Files.delete(file);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        try (Stream<Path> s = Files.list(d)) {
          if (!s.findAny().isPresent()) {
            Files.delete(d);
          }
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  static void delete(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        Files.delete(d);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
