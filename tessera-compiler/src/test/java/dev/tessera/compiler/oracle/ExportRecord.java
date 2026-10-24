package dev.tessera.compiler.oracle;

import java.util.Objects;

/**
 * One line of the JSONL export the gate writes with {@code -Dtessera.oracle.export=<file>} (#324)
 * and the Node job reads (#322): one record per (case, value).
 *
 * <p>Format: {@code {"id":..,"name":..,"template":..,"value":..,"render":..,"verdict":..}}, all
 * strings. {@code value} and {@code render} are null for a rejected case; {@code verdict} is a {@link Verdict#label()}.
 */
public record ExportRecord(
    String id, String name, String template, String value, String render, String verdict) {

  public ExportRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(verdict, "verdict");
  }

  /** A record for {@code value} rendered from {@code oracleCase}. */
  public static ExportRecord of(OracleCase oracleCase, String value, String render, Verdict verdict) {
    return new ExportRecord(
        oracleCase.id(), oracleCase.name(), oracleCase.template(), value, render, verdict.label());
  }

  /** This record as one JSON line, without the trailing newline. */
  public String toJsonLine() {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    field(sb, "id", id).append(',');
    field(sb, "name", name).append(',');
    field(sb, "template", template).append(',');
    field(sb, "value", value).append(',');
    field(sb, "render", render).append(',');
    field(sb, "verdict", verdict);
    return sb.append('}').toString();
  }

  private static StringBuilder field(StringBuilder sb, String name, String value) {
    quote(sb, name).append(':');
    return value == null ? sb.append("null") : quote(sb, value);
  }

  private static StringBuilder quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          // Escape all other controls, and U+2028/U+2029 so the line stays one line for any reader.
          if (c < 0x20 || c == ' ' || c == ' ') {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"');
  }
}
