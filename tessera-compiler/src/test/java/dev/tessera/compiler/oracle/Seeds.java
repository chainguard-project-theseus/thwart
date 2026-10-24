package dev.tessera.compiler.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The seed corpus of the oracle gate (#320): hand-picked templates with the verdict each must get
 * on 0dda2f1, the commit the #306 probe experiment ran on. The gate (#324) runs them and must
 * reproduce those verdicts there; the generator's explore mode (#321) mutates them.
 *
 * <p>Files are {@code src/test/resources/oracle/seeds/*.txt} ({@link #FILES}). Cases are separated
 * by a line that is exactly {@code ====}. Each case starts with header lines, {@code # key: value}
 * (other lines starting with {@code #} are comments), followed by the template body:
 *
 * <pre>
 * # name: 306-A5
 * # expect-0dda2f1: LIVE_SCRIPT
 * # fixed-by: #277 #301
 * &lt;svg&gt;&lt;title&gt;&lt;script&gt;var a = [[s]];&lt;/script&gt;&lt;/title&gt;&lt;/svg&gt;
 * </pre>
 *
 * <ul>
 *   <li>{@code name} (required): unique across all seed files.
 *   <li>{@code expect-0dda2f1} (required): {@code CLEAN}, {@code REJECTED}, or one or more {@link
 *       Finding.Kind}s. A kind list is a lower bound: on 0dda2f1 the gate must report at least
 *       these kinds (on {@code engines}), and it may report more, e.g. {@code STRUCTURE_CHANGED}
 *       or {@code JS_NOT_LITERAL}, which the parse5 oracle these were recorded with doesn't model.
 *   <li>{@code fixed-by}: the issues or PRs that fix it, as {@code #n} tokens. Required when
 *       {@code expect-0dda2f1} lists kinds.
 *   <li>{@code main}: the verdict on main at {@link #MAIN_SNAPSHOT} (after #295-#300 and #340
 *       merged), same grammar as {@code expect-0dda2f1}, recorded the same way. A snapshot, not
 *       kept current: main moves as fixes land, so the gate must not assert it or build
 *       known-holes lines from it. Known holes come from running the gate on the checked-out
 *       commit; this is only a cross-check. Missing means not recorded.
 *   <li>{@code engines}: the engines a listed kind must be seen by, each of them; the default
 *       {@code *} means any one engine (see {@link Expectation#metBy}). The classic {@code
 *       <select>} cases are {@code vnu-1.4} only.
 *   <li>{@code note}: free text.
 * </ul>
 *
 * <p>A body is the lines up to the next {@code ====} or the end of the file, joined with {@code
 * \n}. Characters a text file would hide or mangle (controls other than {@code \n}, and anything
 * outside printable ASCII) are written {@code {U+XXXX}}. The loader wraps a body in {@code <div
 * t:params="String s">...</div>} unless its first tag declares {@code t:params}. A chunk with no
 * body and no keys is a comment block (the file's preamble).
 */
public final class Seeds {

  /** The seed files, classpath resources. */
  public static final List<String> FILES =
      List.of("oracle/seeds/306-probes.txt", "oracle/seeds/open-prs.txt", "oracle/seeds/xss-context.txt");

  /** The main commit the {@code main} verdicts were recorded on. */
  public static final String MAIN_SNAPSHOT = "1c4b2b3";

  /** What a seed's verdict is: the three {@link Verdict} labels. */
  public enum Outcome {
    CLEAN,
    REJECTED,
    FINDINGS
  }

  /**
   * An expected verdict.
   *
   * @param outcome clean, rejected, or findings
   * @param kinds the kinds that must be reported, non-empty exactly for {@link Outcome#FINDINGS}
   */
  public record Expectation(Outcome outcome, Set<Finding.Kind> kinds) {
    public Expectation {
      kinds = Collections.unmodifiableSet(kinds.isEmpty() ? EnumSet.noneOf(Finding.Kind.class) : EnumSet.copyOf(kinds));
      if ((outcome == Outcome.FINDINGS) == kinds.isEmpty()) {
        throw new IllegalArgumentException(outcome + " with kinds " + kinds);
      }
    }

    /** Parses {@code CLEAN}, {@code REJECTED} or a space-separated list of kinds. */
    static Expectation parse(String value) {
      String v = value.strip();
      if (v.equals("CLEAN") || v.equals("REJECTED")) {
        return new Expectation(Outcome.valueOf(v), Set.of());
      }
      Set<Finding.Kind> kinds = EnumSet.noneOf(Finding.Kind.class);
      for (String k : v.split("\\s+")) {
        try {
          kinds.add(Finding.Kind.valueOf(k));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("unknown verdict or finding kind '" + k + "'");
        }
      }
      return new Expectation(Outcome.FINDINGS, kinds);
    }

    /**
     * Whether {@code verdict} meets this expectation: same outcome, and for findings every listed
     * kind reported on each of {@code engines} ({@code *} for any engine). {@code JS_UNPARSABLE}
     * alone is not a finding here: the gate counts it, it doesn't fail on it (#319).
     */
    public boolean metBy(Verdict verdict, List<String> engines) {
      List<Finding> findings = new ArrayList<>();
      if (verdict instanceof Verdict.Findings f) {
        for (Finding x : f.findings()) {
          if (x.kind() != Finding.Kind.JS_UNPARSABLE) {
            findings.add(x);
          }
        }
      }
      return switch (outcome) {
        case REJECTED -> verdict instanceof Verdict.Rejected;
        case CLEAN -> !(verdict instanceof Verdict.Rejected) && findings.isEmpty();
        case FINDINGS ->
            kinds.stream()
                .allMatch(
                    k ->
                        engines.stream()
                            .allMatch(
                                e ->
                                    findings.stream()
                                        .anyMatch(x -> x.kind() == k && (e.equals("*") || e.equals(x.engine())))));
      };
    }

    @Override
    public String toString() {
      return outcome == Outcome.FINDINGS
          ? String.join(" ", kinds.stream().map(Enum::name).toList())
          : outcome.name();
    }
  }

  /**
   * One seed.
   *
   * @param file the resource it was read from
   * @param line the line its header starts on, 1-based
   * @param name unique name
   * @param body the template body as written (escapes decoded)
   * @param template the template the gate compiles: {@code body}, wrapped unless it has its own root
   * @param expected the verdict on 0dda2f1
   * @param fixedBy issue or PR numbers, in file order
   * @param onMain the verdict on main at {@link #MAIN_SNAPSHOT}, or null if not recorded
   * @param engines the engines a listed kind must be seen by; {@code ["*"]} by default
   * @param note free text, or empty
   */
  public record Seed(
      String file,
      int line,
      String name,
      String body,
      String template,
      Expectation expected,
      List<Integer> fixedBy,
      Expectation onMain,
      List<String> engines,
      String note) {

    /** This seed as a gate case. Its id is the id of {@link #template()}. */
    public OracleCase toCase() {
      return OracleCase.of(name, template, OracleCase.Origin.SEED);
    }
  }

  private static final Pattern KEY = Pattern.compile("#\\s*([a-z0-9-]+):\\s*(.*)");
  private static final Pattern ISSUE = Pattern.compile("#([1-9][0-9]{0,8})");
  private static final Pattern ENGINE = Pattern.compile("\\*|[a-z0-9][a-z0-9.-]*");
  private static final Pattern ESCAPE = Pattern.compile("\\{U\\+([0-9A-F]{4,6})\\}");
  private static final Pattern FIRST_TAG = Pattern.compile("\\A\\s*<[A-Za-z][^>]*>");
  private static final Set<String> KEYS =
      Set.of("name", "expect-0dda2f1", "fixed-by", "main", "engines", "note");

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
      if (m.matches()) {
        if (!KEYS.contains(m.group(1))) {
          throw error(file, i, "unknown key '" + m.group(1) + "'");
        }
        if (keys.put(m.group(1), m.group(2).strip()) != null) {
          throw error(file, i, "duplicate key '" + m.group(1) + "'");
        }
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
    int line = from + 1;
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
    String exp = keys.get("expect-0dda2f1");
    if (exp == null) {
      throw error(file, from, "seed " + name + " has no '# expect-0dda2f1:'");
    }
    Expectation expected = expectation(file, from, name, exp);
    List<Integer> fixedBy = new ArrayList<>();
    String fixed = keys.get("fixed-by");
    if (fixed != null) {
      for (String tok : fixed.split("\\s+")) {
        Matcher m = ISSUE.matcher(tok);
        if (!m.matches()) {
          throw error(file, from, "seed " + name + ": fixed-by takes '#<issue>' tokens, got '" + tok + "'");
        }
        fixedBy.add(Integer.parseInt(m.group(1)));
      }
    }
    if (expected.outcome() == Outcome.FINDINGS && fixedBy.isEmpty()) {
      throw error(file, from, "seed " + name + " has findings on 0dda2f1 but no '# fixed-by: #<issue>'");
    }
    String main = keys.get("main");
    Expectation onMain = main == null ? null : expectation(file, from, name, main);
    List<String> engines = List.of("*");
    if (keys.containsKey("engines")) {
      engines = List.of(keys.get("engines").split("\\s+"));
      for (String e : engines) {
        if (!ENGINE.matcher(e).matches()) {
          throw error(file, from, "seed " + name + ": bad engine name '" + e + "'");
        }
      }
    }
    String decoded = unescape(file, from, body);
    return new Seed(
        file, line, name, decoded, wrap(decoded), expected, List.copyOf(fixedBy), onMain, engines,
        keys.getOrDefault("note", ""));
  }

  private static Expectation expectation(String file, int index, String name, String value) {
    try {
      return Expectation.parse(value);
    } catch (IllegalArgumentException e) {
      throw error(file, index, "seed " + name + ": " + e.getMessage());
    }
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
