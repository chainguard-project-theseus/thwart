package dev.tessera.migrate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 */
public final class Main {
  private Main() {}

  static final String USAGE =
      "usage: tessera-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]";

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
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
        reportFile = Path.of(args[i]);
      } else if (arg.startsWith("-")) {
        return usage(err, "unknown option " + arg);
      } else if (in == null) {
        in = Path.of(arg);
      } else if (outDir == null) {
        outDir = Path.of(arg);
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
          .toList();
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
        String source = Files.readString(template, StandardCharsets.UTF_8);
        Migrator.Result result = Migrator.migrate(name, source);
        Path target = outDir.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, result.output(), StandardCharsets.UTF_8);
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
        Files.writeString(reportFile, markdown, StandardCharsets.UTF_8);
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
