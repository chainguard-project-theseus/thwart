package dev.tessera.compiler.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The list of known open holes, {@code src/test/resources/oracle/known-holes.txt}. The gate (#324)
 * and the Node job (#322) fail on a finding that isn't listed and on a listed hole that no longer
 * occurs, so the list only shrinks.
 *
 * <p>One hole per line: {@code <case-id> <KIND> <engine or *> #<issue> <note>}. {@code #} starts a
 * comment line; blank lines are ignored. A line without an issue number is a format error, so every
 * listed hole is tracked.
 */
public final class KnownHoles {

  /** The classpath resource the gate loads. */
  public static final String RESOURCE = "oracle/known-holes.txt";

  /** One listed hole. {@code engine} is {@code *} for every engine. */
  public record Entry(String caseId, Finding.Kind kind, String engine, int issue, String note) {

    /** Whether this entry covers {@code finding} in case {@code caseId}. */
    public boolean matches(String caseId, Finding finding) {
      return this.caseId.equals(caseId)
          && kind == finding.kind()
          && (engine.equals("*") || engine.equals(finding.engine()));
    }
  }

  private static final Pattern LINE =
      Pattern.compile("([0-9a-f]{12})\\s+([A-Z_]+)\\s+(\\S+)\\s+#([1-9][0-9]*)(?:\\s+(.*))?");

  private final List<Entry> entries;

  private KnownHoles(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** The listed holes, in file order. */
  public List<Entry> entries() {
    return entries;
  }

  /** The entry covering {@code finding} in case {@code caseId}, or null if it's a new hole. */
  public Entry find(String caseId, Finding finding) {
    for (Entry e : entries) {
      if (e.matches(caseId, finding)) {
        return e;
      }
    }
    return null;
  }

  /** Loads {@link #RESOURCE} from the test classpath. */
  public static KnownHoles load() {
    try (InputStream in = KnownHoles.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + RESOURCE);
      }
      return parse(RESOURCE, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("reading " + RESOURCE, e);
    }
  }

  /**
   * Parses the file format.
   *
   * @throws IllegalArgumentException naming the file and line on a format error
   */
  public static KnownHoles parse(String file, String text) {
    List<Entry> entries = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String[] lines = text.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      Matcher m = LINE.matcher(line);
      if (!m.matches()) {
        throw error(file, i, "expected '<case-id> <KIND> <engine or *> #<issue> <note>', got: " + line);
      }
      Finding.Kind kind;
      try {
        kind = Finding.Kind.valueOf(m.group(2));
      } catch (IllegalArgumentException e) {
        throw error(file, i, "unknown finding kind " + m.group(2));
      }
      String key = m.group(1) + ' ' + kind + ' ' + m.group(3);
      if (!seen.add(key)) {
        throw error(file, i, "duplicate entry " + key);
      }
      String note = m.group(5) == null ? "" : m.group(5);
      entries.add(new Entry(m.group(1), kind, m.group(3), Integer.parseInt(m.group(4)), note));
    }
    return new KnownHoles(entries);
  }

  private static IllegalArgumentException error(String file, int index, String message) {
    return new IllegalArgumentException(file + ":" + (index + 1) + ": " + message);
  }
}
