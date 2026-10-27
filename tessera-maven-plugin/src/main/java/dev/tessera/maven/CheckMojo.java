package dev.tessera.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Makes {@code compile} run the Tessera annotation processor again when templates changed (#425).
 *
 * <p>maven-compiler-plugin runs javac only when Java sources, the source set or the classpath
 * change, so a template-only edit used to leave the old generated classes and a green build. The
 * processor records the templates it compiled ({@link TemplateFingerprint#RESOURCE} in the class
 * output). Before {@code compile}, this goal compares that record with the templates directory. If
 * a template was added, edited or deleted, or the class output has no record, it deletes the
 * compile output (the main and test class directories, the processors' generated sources and the
 * compiler plugin's incremental state), as {@code mvn clean} would, so the compiler plugin compiles
 * the module from scratch whatever its version or incremental mode. A first build (no output yet)
 * is left alone.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class CheckMojo extends AbstractMojo {
  /** The templates root: the same directory as {@code -Atessera.templates}. */
  @Parameter(defaultValue = "${project.basedir}/src/main/resources/templates", required = true)
  File templates;

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
  File outputDirectory;

  @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
  File testOutputDirectory;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
  File buildDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      byte[] current = templates.isDirectory() ? TemplateFingerprint.of(templates.toPath()) : null;
      String reason = staleReason(outputDirectory.toPath(), current, true);
      if (reason == null) {
        // The processor also runs in test compilation, which writes its own record.
        reason = staleReason(testOutputDirectory.toPath(), current, false);
      }
      if (reason == null) {
        getLog().debug("Tessera: templates unchanged since the last compile");
        return;
      }
      getLog().info("Tessera: " + reason + "; deleting the compile output so the templates are compiled again");
      Path build = buildDirectory.toPath();
      delete(outputDirectory.toPath());
      delete(testOutputDirectory.toPath());
      delete(build.resolve("generated-sources/annotations"));
      delete(build.resolve("generated-test-sources/test-annotations"));
      delete(build.resolve("maven-status/maven-compiler-plugin"));
    } catch (IOException e) {
      throw new MojoExecutionException(
          "Tessera: cannot check whether the templates changed since the last compile (" + e
              + "); run mvn clean compile", e);
    }
  }

  /**
   * Why the output in {@code classes} is stale, or null. Main output without a record is stale (a
   * failed or pre-#425 compile); test output without one is not (its compile may not run the
   * processor).
   */
  static String staleReason(Path classes, byte[] current, boolean main) throws IOException {
    Path recorded = classes.resolve(TemplateFingerprint.RESOURCE);
    if (Files.isRegularFile(recorded)) {
      return current != null && Arrays.equals(current, Files.readAllBytes(recorded))
          ? null
          : "templates changed since the last compile";
    }
    return main && hasFiles(classes) ? "no template fingerprint in " + classes : null;
  }

  private static boolean hasFiles(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> s = Files.list(dir)) {
      return s.findAny().isPresent();
    }
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
