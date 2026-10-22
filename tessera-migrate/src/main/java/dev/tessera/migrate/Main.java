package dev.tessera.migrate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.attoparser.ParseException;

/**
 * {@code tessera-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]}: converts
 * every {@code .html} template from Thymeleaf to Tessera syntax and writes a report listing,
 * per file and line, every construct it could not convert. Without {@code --report} the report
 * goes to standard output.
 *
 * <p>Exit codes: 0 on success (report items do not fail the run; the report is the audit),
 * 1 if a template could not be read, parsed or written, 2 on a usage error.
 *
 * <p>{@code tessera-migrate record|compare <base-url> <pages.txt> <dir>}: the page-parity check
 * (see {@link Parity}). {@code record} saves every page of the running old app to {@code dir};
 * {@code compare} requests the same pages from the running new app and prints {@code same},
 * {@code reordered} or {@code differs} (the last two with a diff) per page. {@code compare} exits 0 only if
 * every page has its recorded status and final URL and is the same or reordered. {@code --ignore
 * REGEX} (repeatable) deletes the matches from both the recorded and the new page first.
 */
public final class Main {
  private Main() {}

  static final String USAGE = String.join(System.lineSeparator(),
      "usage: tessera-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]",
      "       tessera-migrate record <base-url> <pages.txt> <dir>",
      "       tessera-migrate compare <base-url> <pages.txt> <dir> [--ignore REGEX]...",
      "",
      "pages.txt: one request per line, `GET /path` or `POST /path a=1&b=2`; `#` comments.",
      "One cookie jar per run; a POST sends the previous page's _csrf hidden input.",
      "record saves NN.html and index.tsv to <dir> from the old app. compare requests the same",
      "pages from the new app and prints same, reordered or differs (both with a diff) per page;",
      "it exits 0 only if every page has its recorded status and final URL and is the same or",
      "reordered. --ignore REGEX (repeatable) deletes the matches from both pages before comparing.");

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length > 0 && (args[0].equals("record") || args[0].equals("compare"))) {
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
    boolean failed = false;
    for (Path template : templates) {
      Path rel = in.relativize(template);
      String name = rel.toString().replace('\\', '/');
      try {
        String source = Compat.readString(template);
        Migrator.Result result = Migrator.migrate(name, source);
        Path target = outDir.resolve(rel);
        Files.createDirectories(target.getParent());
        Compat.writeString(target, result.output());
        report.addTemplate(result.items());
      } catch (ParseException e) {
        err.println(name + ":" + e.getLine() + ":" + e.getCol() + ": error: " + e.getMessage());
        failed = true;
      } catch (IOException e) {
        err.println(name + ": error: " + e.getMessage());
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
      out.println("tessera-migrate: converted " + report.templates() + " template(s), "
          + report.itemCount() + " report item(s), report written to " + reportFile);
    }
    return failed ? 1 : 0;
  }

  private static int usage(PrintStream err, String problem) {
    if (problem != null) {
      err.println("tessera-migrate: " + problem);
    }
    err.println(USAGE);
    return 2;
  }
}
