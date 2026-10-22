package dev.tessera.compiler;

/**
 * A compiler message tied to a template position. Rendered as
 * {@code owners/ownerDetails.html:12:7: error: message}.
 */
public record Diagnostic(Severity severity, SourcePos pos, String message) {

  public enum Severity {
    ERROR,
    WARNING,
    NOTE
  }

  @Override
  public String toString() {
    return pos + ": " + severity.name().toLowerCase(java.util.Locale.ROOT) + ": " + message;
  }
}
