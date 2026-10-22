package dev.tessera.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Claude Code migration skill in {@code plugins/tessera} is well formed: the
 * {@code SKILL.md} frontmatter, the files it links to, the copy of {@code docs/guide.md} (the single
 * source of truth), the {@code tessera-migrate} coordinates it documents, and the plugin and
 * marketplace manifests that point at it.
 */
class MigrationSkillTest {
  private static final Path ROOT = Path.of("..");
  private static final Path PLUGIN = ROOT.resolve("plugins/tessera");
  private static final Path SKILL_DIR = PLUGIN.resolve("skills/migrate");
  private static final Path GUIDE = ROOT.resolve("docs/guide.md");
  private static final Path GUIDE_COPY = SKILL_DIR.resolve("references/guide.md");

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
  }

  @Test
  void linkedFilesExist() throws IOException {
    String skill = read(SKILL_DIR.resolve("SKILL.md"));
    Matcher link = Pattern.compile("\\]\\(([^)#]+)(#[^)]*)?\\)").matcher(skill);
    int links = 0;
    while (link.find()) {
      String target = link.group(1);
      if (target.contains("://")) {
        continue;
      }
      links++;
      assertTrue(Files.isRegularFile(SKILL_DIR.resolve(target)), "SKILL.md links to a missing file: " + target);
    }
    assertTrue(links > 0, "SKILL.md should link to its reference files");
  }

  @Test
  void guideCopyMatchesDocsGuide() throws IOException {
    assertEquals(read(GUIDE), read(GUIDE_COPY),
        "plugins/tessera/skills/migrate/references/guide.md is stale; docs/guide.md is the source of truth. Run:"
            + " cp docs/guide.md plugins/tessera/skills/migrate/references/guide.md");
  }

  @Test
  void documentedToolCoordinatesMatchTheBuild() throws IOException {
    Matcher v = Pattern.compile("<artifactId>tessera-parent</artifactId>\\s*<version>([^<]+)</version>")
        .matcher(read(ROOT.resolve("pom.xml")));
    assertTrue(v.find(), "no tessera-parent version in pom.xml");
    String version = v.group(1);
    assertTrue(read(ROOT.resolve("tessera-migrate/pom.xml")).contains("<artifactId>tessera-migrate</artifactId>"));

    String skill = read(SKILL_DIR.resolve("SKILL.md"));
    Matcher coords = Pattern.compile("-Dartifact=(\\S+)").matcher(skill);
    int found = 0;
    while (coords.find()) {
      found++;
      assertEquals("dev.tessera:tessera-migrate:" + version, coords.group(1), "SKILL.md documents the wrong artifact");
    }
    assertEquals(1, found, "SKILL.md should state the tessera-migrate coordinates exactly once");
    // The commands run the jar as .tessera/tessera-migrate.jar, which is where dependency:copy
    // puts it with these two flags.
    assertTrue(skill.contains("-Dmdep.stripVersion=true -DoutputDirectory=.tessera"));
    Matcher jar = Pattern.compile("java -jar (\\S+)").matcher(skill);
    while (jar.find()) {
      assertEquals(".tessera/tessera-migrate.jar", jar.group(1));
    }
  }

  @Test
  void manifestsPointAtThePlugin() throws IOException {
    String plugin = read(PLUGIN.resolve(".claude-plugin/plugin.json"));
    assertTrue(plugin.contains("\"name\": \"tessera\""), "plugin.json name must be tessera");
    // No version: installs track the repository's commits (code.claude.com/docs/en/plugins/host-marketplace).
    assertFalse(plugin.contains("\"version\""), "plugin.json should not pin a version");
    String marketplace = read(ROOT.resolve(".claude-plugin/marketplace.json"));
    assertTrue(marketplace.contains("\"name\": \"tessera\""), "marketplace entry name must match plugin.json");
    assertTrue(marketplace.contains("\"source\": \"./plugins/tessera\""), "marketplace entry must point at plugins/tessera");
  }
}
