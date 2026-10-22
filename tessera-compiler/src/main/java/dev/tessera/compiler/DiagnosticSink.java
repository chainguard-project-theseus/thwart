package dev.tessera.compiler;

/** Receives diagnostics from every compiler stage. Stages report and continue where possible. */
@FunctionalInterface
public interface DiagnosticSink {

  void report(Diagnostic diagnostic);

  default void error(SourcePos pos, String message) {
    report(new Diagnostic(Diagnostic.Severity.ERROR, pos, message));
  }

  default void warning(SourcePos pos, String message) {
    report(new Diagnostic(Diagnostic.Severity.WARNING, pos, message));
  }
}
