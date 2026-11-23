package dev.chainguard.thwart.migrate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.attoparser.ParseException;

/**
 * {@code thwart-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]}: converts
 * every {@code .html} template from Thymeleaf to Thwart syntax and writes a report listing,
 * per file and line, every construct it could not convert. Without {@code --report} the report
 * goes to standard output. Exit codes: 0 on success (report items do not fail the run; the report
 * is the audit), 1 if a template could not be read, parsed or written, 2 on a usage error.
 *
 * <p>{@code record} and {@code compare} are the page-parity check ({@link Parity}); {@link #USAGE}
 * documents every command.
 */
public final class Main {
  private Main() {}

  static final String USAGE = String.join(System.lineSeparator(),
      "usage: thwart-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]",
      "       thwart-migrate record <base-url> <pages.txt> <dir>",
      "       thwart-migrate compare <base-url> <pages.txt> <dir> [--ignore REGEX]...",
      "",
      "pages.txt: one request per line, `GET /path` or `POST /path a=1&b=2`; `#` comments.",
      "One cookie jar per run; a POST sends the previous page's _csrf hidden input.",
      "record saves NN.html and index.tsv to <dir> from the old app. compare requests the same",
      "pages from the new app and prints same, reordered, forced (differs only in what Thwart",
      "forces: on* handlers, javascript: and # links, data-* attributes, scripts and styles,",
      "queries in custom attributes, svg in tables, sanitizer rel and ids, quoted CSS urls,",
      "xmlns, CSRF inputs, removed -on: attributes; see the guide) or differs",
      "(all but same with a diff) per page;",
      "it exits 0 only if every page has its recorded status and final URL and is the same or",
      "reordered. --ignore REGEX (repeatable) deletes the matches from both pages before comparing.");

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length > 0 && (args[0].equals("record") || args[0].equals("compare"))) {
      return parity(args, out, err);
    }
    Path in = null;
    Path outDir = null;
    Path reportFile = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("-h") || arg.equals("--help")) {
        out.println(USAGE);
        return 0;
      } else if (arg.equals("--report")) {
        if (++i >= args.length) {
          return usage(err, "--report needs a file name");
        }
        reportFile = Paths.get(args[i]);
      } else if (arg.startsWith("-")) {
        return usage(err, "unknown option " + arg);
      } else if (in == null) {
        in = Paths.get(arg);
      } else if (outDir == null) {
        outDir = Paths.get(arg);
      } else {
        return usage(err, "unexpected argument " + arg);
      }
    }
    if (in == null || outDir == null) {
      return usage(err, null);
    }
    if (!Files.isDirectory(in)) {
      return usage(err, "not a directory: " + in);
    }
    return convert(in, outDir, reportFile, out, err);
  }

  /** The page-parity check; args[0] is {@code record} or {@code compare}. */
  private static int parity(String[] args, PrintStream out, PrintStream err) {
    List<String> positional = new ArrayList<>();
    List<Pattern> ignore = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("--ignore") && args[0].equals("compare")) {
        if (++i >= args.length) {
          return usage(err, "--ignore needs a regular expression");
        }
        try {
          ignore.add(Pattern.compile(args[i]));
        } catch (PatternSyntaxException e) {
          return usage(err, "bad --ignore pattern: " + e.getMessage());
        }
      } else {
        positional.add(args[i]);
      }
    }
    if (positional.size() != 3) {
      return usage(err, args[0] + " needs <base-url> <pages.txt> <dir>");
    }
    Path pages = Paths.get(positional.get(1));
    if (!Files.isRegularFile(pages)) {
      return usage(err, "not a file: " + pages);
    }
    Path dir = Paths.get(positional.get(2));
    return args[0].equals("record")
        ? Parity.record(positional.get(0), pages, dir, LocalDate.now(), out, err)
        : Parity.compare(positional.get(0), pages, dir, LocalDate.now(), ignore, out, err);
  }

  /** Converts every template under {@code in} into {@code outDir} and writes the report. */
  private static int convert(Path in, Path outDir, Path reportFile, PrintStream out,
      PrintStream err) {
    List<Path> templates;
    try (Stream<Path> files = Files.walk(in)) {
      templates = files
          .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".html"))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      err.println("error: cannot list " + in + ": " + e.getMessage());
      return 1;
    }

    Report report = new Report();
    Map<String, String> converted = new LinkedHashMap<>();
    boolean failed = false;
    for (Path template : templates) {
      Path rel = in.relativize(template);
      String name = rel.toString().replace('\\', '/');
      try {
        String source = Compat.readString(template);
        Migrator.Result result = Migrator.migrate(name, source);
        report.addTemplate(result.items());
        converted.put(name, result.output());
      } catch (ParseException e) {
        err.println(name + ":" + e.getLine() + ":" + e.getCol() + ": error: " + e.getMessage());
        failed = true;
      } catch (IOException e) {
        err.println(name + ": error: " + e.getMessage());
        failed = true;
      }
    }
    Layouts.Result layouts = Layouts.rewrite(converted);
    report.addItems(layouts.items());
    for (Map.Entry<String, String> e : layouts.templates().entrySet()) {
      Path target = outDir.resolve(e.getKey());
      try {
        Files.createDirectories(target.getParent());
        Compat.writeString(target, e.getValue());
      } catch (IOException ex) {
        err.println(e.getKey() + ": error: " + ex.getMessage());
        failed = true;
      }
    }

    String markdown = report.toMarkdown();
    if (reportFile == null) {
      out.print(markdown);
    } else {
      try {
        Path parent = reportFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Compat.writeString(reportFile, markdown);
      } catch (IOException e) {
        err.println("error: cannot write report " + reportFile + ": " + e.getMessage());
        return 1;
      }
      out.println("thwart-migrate: converted " + report.templates() + " template(s), "
          + report.itemCount() + " report item(s), report written to " + reportFile);
    }
    return failed ? 1 : 0;
  }

  private static int usage(PrintStream err, String problem) {
    if (problem != null) {
      err.println("thwart-migrate: " + problem);
    }
    err.println(USAGE);
    return 2;
  }
}
