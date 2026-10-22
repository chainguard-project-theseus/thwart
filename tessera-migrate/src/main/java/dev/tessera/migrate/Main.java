package dev.tessera.migrate;

/**
 * {@code tessera-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]}: converts
 * every {@code .html} template from Thymeleaf to Tessera syntax and writes a report listing,
 * per file and line, every construct it could not convert.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: tessera-migrate <thymeleaf-templates-dir> <output-dir> [--report report.md]");
      System.exit(2);
    }
    throw new UnsupportedOperationException("TODO(#9): tessera-migrate");
  }
}
