package dev.chainguard.thwart.migrate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code record} and {@code compare}: the page-parity check for a migration. {@code record} saves
 * every page of the old app; {@code compare} requests the same pages from the new app and says,
 * per page, whether it is the same page (see {@link PageNormalizer}).
 *
 * <p>A recording is plain files: {@code NN.html} holds the body of the NN-th request exactly as
 * received, and {@code index.tsv} lists file, status, final URL (after redirects, relative to the
 * base URL) and the request, one tab-separated line per request.
 *
 * <p>The ISO date of the recording day ({@code 2026-10-22}) in a recorded page, and of today in a
 * new page, both compare as {@code TODAY}, so a form that defaults a date to today does not differ
 * between a recording made yesterday and a comparison run now. Text matching an {@code ignore}
 * pattern is deleted from both pages before they are compared.
 */
final class Parity {
  private Parity() {}

  static final String INDEX = "index.tsv";
  /** Diff lines printed per page; the recording and the app itself have the rest. */
  static final int MAX_DIFF_LINES = 120;
  private static final Pattern HEADER = Pattern.compile("# thwart-migrate record (\\S+) (\\S+)");

  private static final class Entry {
    private final String file;
    private final int status;
    private final String finalUrl;
    private final String request;

    Entry(String file, int status, String finalUrl, String request) {
      this.file = file;
      this.status = status;
      this.finalUrl = finalUrl;
      this.request = request;
    }

    String file() {
      return file;
    }

    int status() {
      return status;
    }

    String finalUrl() {
      return finalUrl;
    }

    String request() {
      return request;
    }
  }

  static int record(String base, Path pages, Path dir, LocalDate today, PrintStream out,
      PrintStream err) {
    List<Crawler.Request> requests;
    try {
      requests = Crawler.readPages(pages);
      Files.createDirectories(dir);
    } catch (IOException e) {
      err.println("thwart-migrate record: " + e.getMessage());
      return 1;
    }
    Crawler crawler = new Crawler(base);
    StringBuilder index = new StringBuilder("# thwart-migrate record " + today + " " + base
        + "\n# file\tstatus\tfinal URL\trequest\n");
    int noResponse = 0;
    for (int i = 0; i < requests.size(); i++) {
      Crawler.Request request = requests.get(i);
      Crawler.Response response = crawler.send(request);
      String file = fileName(i, requests.size());
      try {
        Compat.writeString(dir.resolve(file), response.body());
      } catch (IOException e) {
        err.println("thwart-migrate record: cannot write " + dir.resolve(file) + ": " + e.getMessage());
        return 1;
      }
      index.append(file).append('\t').append(response.status()).append('\t')
          .append(response.finalUrl()).append('\t').append(request.line()).append('\n');
      out.println(number(i, requests.size()) + " " + response.status() + " " + request.line());
      if (response.status() == 0) {
        err.println("  no response: " + response.body());
        noResponse++;
      }
    }
    try {
      Compat.writeString(dir.resolve(INDEX), index);
    } catch (IOException e) {
      err.println("thwart-migrate record: cannot write " + dir.resolve(INDEX) + ": " + e.getMessage());
      return 1;
    }
    out.println("thwart-migrate record: " + requests.size() + " page(s) saved to " + dir
        + (noResponse > 0 ? ", " + noResponse + " without a response" : ""));
    return noResponse > 0 ? 1 : 0;
  }

  static int compare(String base, Path pages, Path dir, LocalDate today, List<Pattern> ignore,
      PrintStream out, PrintStream err) {
    List<Crawler.Request> requests;
    List<Entry> entries = new ArrayList<>();
    String recordedOn;
    try {
      requests = Crawler.readPages(pages);
      List<String> lines = Files.readAllLines(dir.resolve(INDEX), StandardCharsets.UTF_8);
      Matcher header = lines.isEmpty() ? null : HEADER.matcher(lines.get(0));
      if (header == null || !header.matches()) {
        throw new IOException(dir.resolve(INDEX) + " is not a thwart-migrate recording");
      }
      recordedOn = header.group(1);
      for (String line : lines) {
        if (!line.startsWith("#") && !Compat.isBlank(line)) {
          String[] f = line.split("\t", 4);
          entries.add(new Entry(f[0], Integer.parseInt(f[1]), f[2], f[3]));
        }
      }
    } catch (IOException | RuntimeException e) {
      err.println("thwart-migrate compare: " + e.getMessage());
      return 1;
    }
    List<String> recorded = entries.stream().map(Entry::request).collect(Collectors.toList());
    List<String> wanted = requests.stream().map(Crawler.Request::line).collect(Collectors.toList());
    if (!recorded.equals(wanted)) {
      err.println("thwart-migrate compare: " + pages + " does not list the requests recorded in "
          + dir + "; record again");
      return 1;
    }

    Crawler crawler = new Crawler(base);
    int same = 0;
    int reordered = 0;
    int forced = 0;
    int differ = 0;
    for (int i = 0; i < requests.size(); i++) {
      Entry entry = entries.get(i);
      Crawler.Response response = crawler.send(requests.get(i));
      String expected;
      try {
        expected = Compat.readString(dir.resolve(entry.file()));
      } catch (IOException e) {
        err.println("thwart-migrate compare: cannot read " + dir.resolve(entry.file()) + ": "
            + e.getMessage());
        return 1;
      }
      expected = expected.replace(recordedOn, "TODAY");
      String actual = response.body().replace(today.toString(), "TODAY");
      for (Pattern p : ignore) {
        expected = p.matcher(expected).replaceAll("");
        actual = p.matcher(actual).replaceAll("");
      }
      PageNormalizer.Verdict verdict = PageNormalizer.compare(expected, actual);
      boolean statusOk = entry.status() == response.status();
      boolean urlOk = PageNormalizer.maskUrl(entry.finalUrl())
          .equals(PageNormalizer.maskUrl(response.finalUrl()));
      String word;
      if (statusOk && urlOk && verdict == PageNormalizer.Verdict.SAME) {
        word = "same";
        same++;
      } else if (statusOk && urlOk && verdict == PageNormalizer.Verdict.REORDERED) {
        word = "reordered";
        reordered++;
      } else if (statusOk && urlOk && verdict == PageNormalizer.Verdict.FORCED) {
        word = "forced";
        forced++;
      } else {
        word = "differs";
        differ++;
      }
      out.printf("%s %-9s %s%n", number(i, requests.size()), word, entry.request());
      if (!statusOk) {
        out.println("   status: recorded " + entry.status() + ", got " + response.status()
            + (response.status() == 0 ? " (" + response.body() + ")" : ""));
      }
      if (!urlOk) {
        out.println("   final URL: recorded " + entry.finalUrl() + ", got " + response.finalUrl());
      }
      // A reordered page passes but still shows what moved: HashSet-ordered messages are
      // harmless, table rows that lost their sort order are not, and only a reader can tell.
      if (verdict != PageNormalizer.Verdict.SAME) {
        printDiff(out, entry, expected, actual);
      } else if (word.equals("differs")) {
        out.println("   page: same");
      }
    }
    out.println("thwart-migrate compare: " + requests.size() + " page(s): " + same + " same, "
        + reordered + " reordered, " + forced + " forced, " + differ + " differs"
        + (differ + forced == 0 ? ""
            : "; fix the differs pages first, then check the values in each forced page's script"
                + " diff and its scripts in a browser"));
    return differ + forced == 0 ? 0 : 1;
  }

  private static void printDiff(PrintStream out, Entry entry, String expected, String actual) {
    List<String> hunks = LineDiff.unified(PageNormalizer.lines(expected), PageNormalizer.lines(actual));
    out.println("--- recorded " + entry.file());
    out.println("+++ now");
    for (int j = 0; j < hunks.size() && j < MAX_DIFF_LINES; j++) {
      out.println(hunks.get(j));
    }
    if (hunks.size() > MAX_DIFF_LINES) {
      out.println("... " + (hunks.size() - MAX_DIFF_LINES) + " more diff line(s)");
    }
  }

  private static String number(int i, int count) {
    return String.format("%0" + Math.max(2, String.valueOf(count).length()) + "d", i + 1);
  }

  private static String fileName(int i, int count) {
    return number(i, count) + ".html";
  }
}
