package dev.tessera.compiler;

import java.util.ArrayList;
import java.util.List;

/** A {@link DiagnosticSink} that collects everything reported. */
public final class Diagnostics implements DiagnosticSink {
  private final List<Diagnostic> all = new ArrayList<>();

  @Override
  public void report(Diagnostic diagnostic) {
    all.add(diagnostic);
  }

  public List<Diagnostic> all() {
    return Backports.listCopy(all);
  }

  public boolean hasErrors() {
    return all.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
  }
}
