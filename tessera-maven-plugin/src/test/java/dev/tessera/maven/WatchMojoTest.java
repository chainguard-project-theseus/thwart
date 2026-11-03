package dev.tessera.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #426: the watch compiles once per change to a template or message file, and only then. */
class WatchMojoTest {
  @TempDir Path base;
  private Path templates;
  private int compiles;
  private int exitCode;
  private WatchMojo mojo;

  @BeforeEach
  void setUp() throws Exception {
    templates = base.resolve("src/main/resources/templates");
    write(templates.resolve("welcome.html"), "<p>v1</p>");
    write(base.resolve("src/main/resources/messages.properties"), "welcome=Hello");
    mojo = new WatchMojo() {
      @Override
      int compile() {
        compiles++;
        return exitCode;
      }
    };
    mojo.templates = templates.toFile();
    mojo.messages = "src/main/resources/messages";
    mojo.basedir = base.toFile();
    mojo.pom = base.resolve("pom.xml").toFile();
    mojo.step(); // the first look records the starting state
    compiles = 0;
  }

  @Test
  void nothingChangedNothingCompiles() throws Exception {
    assertFalse(mojo.step());
    assertEquals(0, compiles);
  }

  @Test
  void templateEditCompilesOnce() throws Exception {
    write(templates.resolve("welcome.html"), "<p>v2</p>");
    assertTrue(mojo.step());
    assertFalse(mojo.step());
    assertEquals(1, compiles);
  }

  @Test
  void addedAndDeletedTemplatesCompile() throws Exception {
    write(templates.resolve("owners/list.html"), "<p>list</p>");
    assertTrue(mojo.step());
    Files.delete(templates.resolve("owners/list.html"));
    assertTrue(mojo.step());
    assertEquals(2, compiles);
  }

  @Test
  void messageEditCompiles() throws Exception {
    write(base.resolve("src/main/resources/messages_fr.properties"), "welcome=Bonjour");
    assertTrue(mojo.step());
    assertEquals(1, compiles);
  }

  @Test
  void saveWithSameContentDoesNotCompile() throws Exception {
    write(templates.resolve("welcome.html"), "<p>v1</p>");
    assertFalse(mojo.step());
    assertEquals(0, compiles);
  }

  @Test
  void otherFilesDoNotCompile() throws Exception {
    write(templates.resolve("notes.txt"), "not a template");
    write(base.resolve("src/main/resources/application.properties"), "a=b");
    assertFalse(mojo.step());
  }

  @Test
  void failedCompileKeepsWatching() throws Exception {
    exitCode = 1;
    write(templates.resolve("welcome.html"), "<p t:text=\"${nope}\">v2</p>");
    assertTrue(mojo.step());
    assertFalse(mojo.step()); // not retried until the next save
    exitCode = 0;
    write(templates.resolve("welcome.html"), "<p>v3</p>");
    assertTrue(mojo.step());
    assertEquals(2, compiles);
  }

  @Test
  void compilesThisModuleWithTheRunningMaven() {
    List<String> cmd = mojo.command();
    assertTrue(new File(cmd.get(0)).getName().matches("mvn(\\.cmd)?"), cmd.toString());
    assertEquals(List.of("-q", "-f", mojo.pom.getPath(), "compile"), cmd.subList(1, cmd.size()));
    mojo.offline = true;
    assertTrue(mojo.command().contains("-o"));
  }

  @Test
  void passesOnTheCommandLinesProfilesAndProperties() {
    mojo.activeProfiles = List.of("dev");
    mojo.inactiveProfiles = List.of("ci");
    mojo.userProperties.setProperty("tessera.messages", "src/main/resources/i18n/messages");
    mojo.userProperties.setProperty("maven.repo.local", "/tmp/repo");
    List<String> cmd = mojo.command();
    assertEquals(
        List.of("-Pdev", "-P!ci", "-Dmaven.repo.local=/tmp/repo", "-Dtessera.messages=src/main/resources/i18n/messages",
            "compile"),
        cmd.subList(4, cmd.size()));
  }

  private static void write(Path file, String content) throws Exception {
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
  }
}
