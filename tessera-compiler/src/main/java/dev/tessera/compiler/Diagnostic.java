package dev.tessera.compiler;

import java.util.Objects;

/**
 * A compiler message tied to a template position. Rendered as
 * {@code owners/ownerDetails.html:12:7: error: message}.
 */
public final class Diagnostic {
  private final Severity severity;
  private final SourcePos pos;
  private final String message;

  public Diagnostic(Severity severity, SourcePos pos, String message) {
    this.severity = severity;
    this.pos = pos;
    this.message = message;
  }

  public Severity severity() {
    return severity;
  }

  public SourcePos pos() {
    return pos;
  }

  public String message() {
    return message;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Diagnostic)) {
      return false;
    }
    Diagnostic that = (Diagnostic) o;
    return Objects.equals(severity, that.severity)
        && Objects.equals(pos, that.pos)
        && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(severity);
    h = 31 * h + Objects.hashCode(pos);
    h = 31 * h + Objects.hashCode(message);
    return h;
  }

  public enum Severity {
    ERROR,
    WARNING
  }

  @Override
  public String toString() {
    return pos + ": " + severity.name().toLowerCase(java.util.Locale.ROOT) + ": " + message;
  }
}
