package dev.chainguard.thwart.compiler.guide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The guide in {@code docs/guide/}, whose files are also the Thwart skill's references
 * ({@link ThwartSkillTest}). Examples marked {@code <!-- compile: ... -->} may use types declared in
 * another file, so tests read them all together.
 */
public final class GuideFiles {
  /** The directory, relative to a module's working directory. */
  public static final Path DIR = Path.of("../docs/guide");

  /** The guide's files, in reading order; {@code README.md} is only an index. */
  public static final List<String> NAMES = List.of("setup.md", "templates.md", "migrate.md", "limitations.md");

  private GuideFiles() {}

  /** Every guide file, concatenated in {@link #NAMES} order. */
  public static String readAll() throws IOException {
    StringBuilder all = new StringBuilder();
    for (String name : NAMES) {
      all.append(Files.readString(DIR.resolve(name))).append('\n');
    }
    return all.toString();
  }
}
