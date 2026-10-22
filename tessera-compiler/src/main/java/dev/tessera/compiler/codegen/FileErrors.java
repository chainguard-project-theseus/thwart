package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.DiagnosticSink;
import java.util.HashSet;
import java.util.Set;

/**
 * Forwards every diagnostic and remembers which template files had errors, so the generator can
 * emit nothing for a template with errors. {@code TemplateCompiler} installs it as the
 * {@code CompilerEnv} sink so every stage reports through it.
 */
public final class FileErrors implements DiagnosticSink {
  private final DiagnosticSink delegate;
  private final Set<String> files = new HashSet<>();

  public FileErrors(DiagnosticSink delegate) {
    this.delegate = delegate;
  }

  @Override
  public void report(Diagnostic diagnostic) {
    if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
      files.add(diagnostic.pos() == null ? "" : diagnostic.pos().file());
    }
    delegate.report(diagnostic);
  }

  public boolean hasErrors() {
    return !files.isEmpty();
  }

  public boolean hasErrors(String file) {
    return files.contains(file);
  }
}
