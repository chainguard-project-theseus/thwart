package dev.chainguard.thwart.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Claude Code skill in {@code plugins/thwart} is well formed: the {@code SKILL.md}
 * frontmatter, the files it links to, its references (byte copies of the guide in
 * {@code docs/guide/}, the single source of truth), the {@code thwart-migrate} coordinates they
 * document, and the plugin and marketplace manifests that point at it.
 */
class ThwartSkillTest {
  private static final Path ROOT = Path.of("..");
  private static final Path PLUGIN = ROOT.resolve("plugins/thwart");
  private static final Path SKILL_DIR = PLUGIN.resolve("skills/thwart");
  private static final Path REFERENCES = SKILL_DIR.resolve("references");

  private static String read(Path p) throws IOException {
    return Files.readString(p);
  }

  @Test
  void frontmatterHasNameAndDescription() throws IOException {
    String skill = read(SKILL_DIR.resolve("SKILL.md"));
    Matcher fm = Pattern.compile("\\A---\\n(.*?)\\n---\\n", Pattern.DOTALL).matcher(skill);
    assertTrue(fm.find(), "SKILL.md must start with a --- frontmatter block");
    Matcher name = Pattern.compile("(?m)^name: *(\\S.*)$").matcher(fm.group(1));
    assertTrue(name.find(), "frontmatter has no name");
    assertEquals(SKILL_DIR.getFileName().toString(), name.group(1).trim(), "name must match the skill directory");
    Matcher description = Pattern.compile("(?m)^description: *(\\S.*)$").matcher(fm.group(1));
    assertTrue(description.find(), "frontmatter has no description");
    // Claude Code truncates description + when_to_use at 1,536 characters in the skill listing.
    assertTrue(description.group(1).length() <= 1536, "description is longer than 1536 characters");
    // A plain YAML scalar ends at ": " or " #".
    assertFalse(description.group(1).contains(": ") || description.group(1).contains(" #"),
        "description must stay a plain YAML scalar (no ': ' or ' #')");
  }

  @Test
  void linkedFilesExist() throws IOException {
    String skill = read(SKILL_DIR.resolve("SKILL.md"));
    Matcher link = Pattern.compile("\\]\\(([^)#]+)(#[^)]*)?\\)").matcher(skill);
    List<String> linked = new ArrayList<>();
    while (link.find()) {
      String target = link.group(1);
      if (target.contains("://")) {
        continue;
      }
      linked.add(target);
      assertTrue(Files.isRegularFile(SKILL_DIR.resolve(target)), "SKILL.md links to a missing file: " + target);
    }
    for (String name : GuideFiles.NAMES) {
      assertTrue(linked.contains("references/" + name), "SKILL.md doesn't link to references/" + name);
    }
  }

  @Test
  void referencesAreCopiesOfTheGuide() throws IOException {
    List<String> references;
    try (Stream<Path> files = Files.list(REFERENCES)) {
      references = files.map(p -> p.getFileName().toString()).sorted().toList();
    }
    assertEquals(GuideFiles.NAMES.stream().sorted().toList(), references,
        "plugins/thwart/skills/thwart/references must hold exactly the guide's files");
    for (String name : GuideFiles.NAMES) {
      assertEquals(read(GuideFiles.DIR.resolve(name)), read(REFERENCES.resolve(name)),
          "plugins/thwart/skills/thwart/references/" + name + " is stale; docs/guide/" + name
              + " is the source of truth. Run: cp docs/guide/" + name + " plugins/thwart/skills/thwart/references/");
    }
  }

  @Test
  void documentedToolCoordinatesMatchTheBuild() throws IOException {
    Matcher v = Pattern.compile("<artifactId>thwart-parent</artifactId>\\s*<version>([^<]+)</version>")
        .matcher(read(ROOT.resolve("pom.xml")));
    assertTrue(v.find(), "no thwart-parent version in pom.xml");
    String version = v.group(1);
    assertTrue(read(ROOT.resolve("thwart-migrate/pom.xml")).contains("<artifactId>thwart-migrate</artifactId>"));

    String skill = read(SKILL_DIR.resolve("SKILL.md")) + GuideFiles.readAll();
    Matcher coords = Pattern.compile("-Dartifact=(\\S+)").matcher(skill);
    int found = 0;
    while (coords.find()) {
      found++;
      assertEquals("dev.chainguard.thwart:thwart-migrate:" + version, coords.group(1), "the skill documents the wrong artifact");
    }
    assertEquals(1, found, "the skill should state the thwart-migrate coordinates exactly once");
    // The fetch command runs the jar as .thwart/thwart-migrate.jar, which is where
    // dependency:copy puts it with these two flags.
    assertTrue(skill.contains("-Dmdep.stripVersion=true -DoutputDirectory=.thwart\n  java -jar .thwart/thwart-migrate.jar"));
  }

  @Test
  void manifestsPointAtThePlugin() throws IOException {
    String plugin = read(PLUGIN.resolve(".claude-plugin/plugin.json"));
    assertTrue(plugin.contains("\"name\": \"thwart\""), "plugin.json name must be thwart");
    // No version: installs track the repository's commits (code.claude.com/docs/en/plugins/host-marketplace).
    assertFalse(plugin.contains("\"version\""), "plugin.json should not pin a version");
    String marketplace = read(ROOT.resolve(".claude-plugin/marketplace.json"));
    assertTrue(marketplace.contains("\"name\": \"thwart\""), "marketplace entry name must match plugin.json");
    assertTrue(marketplace.contains("\"source\": \"./plugins/thwart\""), "marketplace entry must point at plugins/thwart");
  }
}
