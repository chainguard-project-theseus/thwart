package dev.chainguard.thwart.compiler.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The seed corpus of the oracle gate (#320): hand-picked templates that {@code ProfileOracleTest}
 * runs and {@code SeedVerifierTest} holds to the verdicts in {@code verify/seed-expectations.txt}.
 *
 * <p>Files are {@code src/test/resources/oracle/seeds/*.txt} ({@link #FILES}). Cases are separated
 * by a line that is exactly {@code ====}. Each case starts with header lines: {@code # name: ...}
 * and an optional {@code # note: ...}. Every other line starting with {@code #} is a comment (the
 * older seeds keep the verdicts they had on 0dda2f1 there, as history). The template body follows:
 *
 * <pre>
 * # name: 306-A5
 * &lt;svg&gt;&lt;title&gt;&lt;script&gt;var a = [[s]];&lt;/script&gt;&lt;/title&gt;&lt;/svg&gt;
 * </pre>
 *
 * <p>A name is unique across all seed files. A body is the lines up to the next {@code ====} or the
 * end of the file, joined with {@code \n}. Characters a text file would hide or mangle (controls
 * other than {@code \n}, and anything outside printable ASCII) are written {@code {U+XXXX}}. The
 * loader wraps a body in {@code <div t:params="String s">...</div>} unless its first tag declares
 * {@code t:params}. A chunk with no body and no keys is a comment block (the file's preamble).
 */
public final class Seeds {

  /** The seed files, classpath resources. */
  public static final List<String> FILES =
      List.of(
          "oracle/seeds/306-probes.txt", "oracle/seeds/open-prs.txt", "oracle/seeds/xss-context.txt",
          "oracle/seeds/reject-678.txt", "oracle/seeds/review-probes.txt", "oracle/seeds/inline-397.txt");

  /**
   * One seed.
   *
   * @param file the resource it was read from
   * @param line the line its header starts on, 1-based
   * @param name unique name
   * @param body the template body as written (escapes decoded)
   * @param template the template the gate compiles: {@code body}, wrapped unless it has its own root
   * @param note free text, or empty
   */
  public record Seed(String file, int line, String name, String body, String template, String note) {

    /** This seed as a gate case. Its id is the id of {@link #template()}. */
    public OracleCase toCase() {
      return OracleCase.of(name, template);
    }
  }

  private static final Pattern KEY = Pattern.compile("#\\s*([a-z0-9-]+):\\s*(.*)");
  private static final Pattern ESCAPE = Pattern.compile("\\{U\\+([0-9A-F]{4,6})\\}");
  private static final Pattern FIRST_TAG = Pattern.compile("\\A\\s*<[A-Za-z][^>]*>");
  private static final Set<String> KEYS = Set.of("name", "note");

  private Seeds() {}

  /** Every seed of every file in {@link #FILES}; names and templates are unique across them. */
  public static List<Seed> load() {
    List<Seed> all = new ArrayList<>();
    for (String file : FILES) {
      all.addAll(parse(file, read(file)));
    }
    Map<String, Seed> names = new LinkedHashMap<>();
    Map<String, Seed> ids = new LinkedHashMap<>();
    for (Seed s : all) {
      Seed other = names.putIfAbsent(s.name(), s);
      if (other != null) {
        throw new IllegalArgumentException(
            s.file() + ":" + s.line() + ": duplicate seed name " + s.name() + " (also " + other.file() + ":" + other.line() + ")");
      }
      other = ids.putIfAbsent(OracleCase.idOf(s.template()), s);
      if (other != null) {
        throw new IllegalArgumentException(
            s.file() + ":" + s.line() + ": seed " + s.name() + " has the same template as " + other.name());
      }
    }
    return List.copyOf(all);
  }

  /** {@link #load()} as gate cases. */
  public static List<OracleCase> cases() {
    return load().stream().map(Seed::toCase).toList();
  }

  /** Wraps a body in {@code <div t:params="String s">} unless its first tag declares {@code t:params}. */
  public static String wrap(String body) {
    Matcher m = FIRST_TAG.matcher(body);
    if (m.find() && m.group().contains("t:params=")) {
      return body;
    }
    return "<div t:params=\"String s\">" + body + "</div>";
  }

  /**
   * Parses one seed file.
   *
   * @throws IllegalArgumentException naming the file and line on a format error
   */
  public static List<Seed> parse(String file, String text) {
    List<Seed> seeds = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    int start = 0;
    for (int i = 0; i <= lines.length; i++) {
      if (i == lines.length || lines[i].equals("====")) {
        Seed s = chunk(file, lines, start, i);
        if (s != null) {
          seeds.add(s);
        }
        start = i + 1;
      }
    }
    return seeds;
  }

  private static Seed chunk(String file, String[] lines, int from, int to) {
    Map<String, String> keys = new LinkedHashMap<>();
    int i = from;
    for (; i < to && lines[i].startsWith("#"); i++) {
      Matcher m = KEY.matcher(lines[i]);
      if (m.matches() && KEYS.contains(m.group(1)) && keys.put(m.group(1), m.group(2).strip()) != null) {
        throw error(file, i, "duplicate key '" + m.group(1) + "'");
      }
    }
    int end = to;
    if (end > i && lines[end - 1].isEmpty() && to == lines.length) {
      end--; // the file's final newline
    }
    String body = String.join("\n", java.util.Arrays.asList(lines).subList(i, Math.max(i, end)));
    if (keys.isEmpty() && body.isBlank()) {
      return null;
    }
    String name = keys.get("name");
    if (name == null || name.isEmpty()) {
      throw error(file, from, "case without '# name:'");
    }
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw error(file, from, "seed name '" + name + "' must be letters, digits, '.', '_' or '-'");
    }
    if (body.isBlank()) {
      throw error(file, from, "seed " + name + " has no template");
    }
    String decoded = unescape(file, from, body);
    return new Seed(file, from + 1, name, decoded, wrap(decoded), keys.getOrDefault("note", ""));
  }

  /** Decodes {@code {U+XXXX}} escapes. */
  static String unescape(String file, int index, String body) {
    Matcher m = ESCAPE.matcher(body);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      int cp = Integer.parseInt(m.group(1), 16);
      if (cp > Character.MAX_CODE_POINT) {
        throw error(file, index, "bad escape " + m.group());
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * The inverse of {@link #unescape}: how a template is written in a seed file. A literal {@code {}
   * that would start an escape is written {@code {U+007B}}, so {@code unescape(escape(s))} is
   * {@code s}.
   */
  public static String escape(String body) {
    StringBuilder sb = new StringBuilder();
    Matcher escape = ESCAPE.matcher(body);
    for (int i = 0; i < body.length(); ) {
      int cp = body.codePointAt(i);
      boolean literal = cp == '{' && escape.region(i, body.length()).lookingAt();
      if (!literal && (cp == '\n' || (cp >= 0x20 && cp < 0x7f))) {
        sb.appendCodePoint(cp);
      } else {
        sb.append(String.format("{U+%04X}", cp));
      }
      i += Character.charCount(cp);
    }
    return sb.toString();
  }

  private static String read(String resource) {
    try (InputStream in = Seeds.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("reading " + resource, e);
    }
  }

  private static IllegalArgumentException error(String file, int index, String message) {
    return new IllegalArgumentException(file + ":" + (index + 1) + ": " + message);
  }
}
