package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #425: the compile output is deleted exactly when the templates differ from the last compile's. */
class CheckMojoTest {
  @TempDir Path base;
  private Path templates;
  private Path target;
  private Path classes;
  private Path testClasses;
  private CheckMojo mojo;

  @BeforeEach
  void setUp() throws Exception {
    templates = base.resolve("src/main/resources/templates");
    target = base.resolve("target");
    classes = target.resolve("classes");
    testClasses = target.resolve("test-classes");
    write(templates.resolve("welcome.html"), "<p>v1</p>");
    mojo = new CheckMojo();
    mojo.templates = templates.toFile();
    mojo.outputDirectory = classes.toFile();
    mojo.testOutputDirectory = testClasses.toFile();
    mojo.buildDirectory = target.toFile();
  }

  /** What a successful compile leaves: classes, generated sources, compiler state, the record. */
  private void compiled() throws Exception {
    byte[] fp = TemplateFingerprint.of(templates);
    write(classes.resolve("com/example/WelcomeTemplate.class"), "x");
    Files.createDirectories(classes.resolve(TemplateFingerprint.RESOURCE).getParent());
    Files.write(classes.resolve(TemplateFingerprint.RESOURCE), fp);
    write(target.resolve("generated-sources/annotations/com/example/WelcomeTemplate.java"), "x");
    write(target.resolve("maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst"), "x");
  }

  private void assertDeleted(boolean deleted) {
    assertTrue(Files.exists(classes) != deleted);
    assertTrue(Files.exists(target.resolve("generated-sources/annotations")) != deleted);
    assertTrue(Files.exists(target.resolve("maven-status/maven-compiler-plugin")) != deleted);
  }

  @Test
  void firstBuildIsLeftAlone() throws Exception {
    mojo.execute();
    assertFalse(Files.exists(target));
  }

  @Test
  void unchangedTemplatesKeepTheOutput() throws Exception {
    compiled();
    mojo.execute();
    assertDeleted(false);
  }

  @Test
  void editedTemplateDeletesTheOutput() throws Exception {
    compiled();
    write(templates.resolve("welcome.html"), "<p>v2</p>");
    mojo.execute();
    assertDeleted(true);
    assertTrue(Files.exists(templates.resolve("welcome.html")));
  }

  @Test
  void addedTemplateDeletesTheOutput() throws Exception {
    compiled();
    write(templates.resolve("owners/list.html"), "<p>new</p>");
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void deletedTemplateDeletesTheOutput() throws Exception {
    write(templates.resolve("old.html"), "<p>old</p>");
    compiled();
    Files.delete(templates.resolve("old.html"));
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void missingTemplatesDirectoryDeletesTheOutput() throws Exception {
    compiled();
    CheckMojo.delete(templates);
    mojo.execute();
    assertFalse(Files.exists(classes));
  }

  @Test
  void outputWithoutARecordIsDeleted() throws Exception {
    compiled();
    Files.delete(classes.resolve(TemplateFingerprint.RESOURCE));
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void staleTestCompileDeletesTheOutput() throws Exception {
    compiled();
    Files.createDirectories(testClasses.resolve(TemplateFingerprint.RESOURCE).getParent());
    Files.write(testClasses.resolve(TemplateFingerprint.RESOURCE), "tessera-templates 1\n".getBytes(StandardCharsets.UTF_8));
    mojo.execute();
    assertDeleted(true);
    assertFalse(Files.exists(testClasses));
  }

  @Test
  void testOutputWithoutARecordIsKept() throws Exception {
    compiled();
    write(testClasses.resolve("com/example/WelcomeTest.class"), "x");
    mojo.execute();
    assertDeleted(false);
    assertTrue(Files.exists(testClasses));
  }

  private static void write(Path p, String s) throws Exception {
    Files.createDirectories(p.getParent());
    Files.write(p, s.getBytes(StandardCharsets.UTF_8));
  }
}
