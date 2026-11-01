package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #425, #447: the compile output is deleted exactly when the templates or messages differ from the
 * last compile's.
 */
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
    mojo.basedir = base.toFile();
  }

  /** What a successful compile leaves: classes, generated sources, compiler state, the record. */
  private void compiled() throws Exception {
    byte[] fp = TemplateFingerprint.of(templates, mojo.messagePaths());
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
  void firstBuildKeepsFilesOtherPluginsWrote() throws Exception {
    // #626: after mvn clean, git-commit-id (phase initialize) writes git.properties before any
    // compile has written a record.
    write(classes.resolve("git.properties"), "git.commit.id=abc");
    write(classes.resolve("templates/welcome.html"), "<p>v1</p>");
    mojo.execute();
    assertTrue(Files.exists(classes.resolve("git.properties")));
    assertTrue(Files.exists(classes.resolve("templates/welcome.html")));
  }

  @Test
  void staleOutputKeepsFilesOtherPluginsWrote() throws Exception {
    compiled();
    write(classes.resolve("git.properties"), "git.commit.id=abc");
    write(classes.resolve("templates/welcome.html"), "<p>v1</p>");
    write(classes.resolve("META-INF/services/com.example.Other"), "com.example.OtherImpl");
    write(classes.resolve(CheckMojo.SERVICES_FILE), "com.example.TemplateRegistryImpl");
    write(classes.resolve("META-INF/tessera/functions/app"), "com.example.Functions");
    write(classes.resolve("com/example/Outer$Inner.class"), "x");
    write(testClasses.resolve("com/example/WelcomeTest.class"), "x");
    write(testClasses.resolve("test.properties"), "a=b");
    write(templates.resolve("welcome.html"), "<p>v2</p>");
    mojo.execute();
    assertTrue(Files.exists(classes.resolve("git.properties")));
    assertTrue(Files.exists(classes.resolve("templates/welcome.html")));
    assertTrue(Files.exists(classes.resolve("META-INF/services/com.example.Other")));
    assertTrue(Files.exists(testClasses.resolve("test.properties")));
    // Everything javac and the processor wrote is gone, and the directories it leaves empty.
    assertFalse(Files.exists(classes.resolve("com")));
    assertFalse(Files.exists(classes.resolve("META-INF/tessera")));
    assertFalse(Files.exists(classes.resolve(CheckMojo.SERVICES_FILE)));
    assertFalse(Files.exists(testClasses.resolve("com")));
    assertFalse(Files.exists(target.resolve("generated-sources/annotations")));
    assertFalse(Files.exists(target.resolve("maven-status/maven-compiler-plugin")));
  }

  @Test
  void outputWithoutARecordOrClassesIsKept() throws Exception {
    write(classes.resolve("application.properties"), "a=b");
    write(target.resolve("maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst"), "x");
    mojo.execute();
    assertTrue(Files.exists(classes.resolve("application.properties")));
    assertTrue(Files.exists(target.resolve("maven-status/maven-compiler-plugin")));
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
  void classesWithoutARecordAreDeletedBesideOtherFiles() throws Exception {
    // A failed or pre-#425 compile: class files but no record, next to a file another plugin wrote.
    write(classes.resolve("git.properties"), "git.commit.id=abc");
    write(classes.resolve("com/example/WelcomeTemplate.class"), "x");
    mojo.execute();
    assertFalse(Files.exists(classes.resolve("com")));
    assertTrue(Files.exists(classes.resolve("git.properties")));
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

  private Path messages() throws Exception {
    Path dir = base.resolve("src/main/resources");
    write(dir.resolve("messages.properties"), "greeting=Hello");
    write(dir.resolve("messages_fr.properties"), "greeting=Bonjour");
    mojo.messages = " src/main/resources/messages , ";
    return dir;
  }

  @Test
  void unchangedMessagesKeepTheOutput() throws Exception {
    messages();
    compiled();
    mojo.execute();
    assertDeleted(false);
  }

  @Test
  void editedMessageDeletesTheOutput() throws Exception {
    Path dir = messages();
    compiled();
    write(dir.resolve("messages.properties"), "greeting=Hi");
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void editedLocaleFileDeletesTheOutput() throws Exception {
    Path dir = messages();
    compiled();
    write(dir.resolve("messages_fr.properties"), "greeting=<b onclick=\"alert(1)\">Salut</b>");
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void addedOrDeletedLocaleFileDeletesTheOutput() throws Exception {
    Path dir = messages();
    compiled();
    write(dir.resolve("messages_pt_BR.properties"), "greeting=Oi");
    mojo.execute();
    assertDeleted(true);
    compiled();
    Files.delete(dir.resolve("messages_fr.properties"));
    mojo.execute();
    assertDeleted(true);
  }

  @Test
  void otherPropertiesFilesAreIgnored() throws Exception {
    Path dir = messages();
    compiled();
    write(dir.resolve("application.properties"), "server.port=8081");
    mojo.execute();
    assertDeleted(false);
  }

  @Test
  void recordFromAnotherVersionDeletesTheOutput() throws Exception {
    compiled();
    Files.write(classes.resolve(TemplateFingerprint.RESOURCE), "tessera-templates 1\n".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "the template fingerprint is from another version of Tessera",
        CheckMojo.staleReason(classes, TemplateFingerprint.of(templates, mojo.messagePaths()), true));
    mojo.execute();
    assertDeleted(true);
  }

  /** The processor read bundles the goal was not told about: recompile, and say how to fix it. */
  @Test
  void bundlesTheGoalDoesNotKnowDeleteTheOutputAndSaySo() throws Exception {
    messages();
    compiled();
    mojo.messages = null;
    String reason = CheckMojo.staleReason(classes, TemplateFingerprint.of(templates, mojo.messagePaths()), true);
    assertTrue(reason.startsWith(CheckMojo.OTHER_MESSAGES) && reason.contains("[messages]"), reason);
    mojo.execute();
    assertDeleted(true);
  }

  private static void write(Path p, String s) throws Exception {
    Files.createDirectories(p.getParent());
    Files.write(p, s.getBytes(StandardCharsets.UTF_8));
  }
}
