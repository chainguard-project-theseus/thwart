package dev.tessera.compiler.oracle;

import java.util.List;

/**
 * The gate's result for one case (#324): the compiler rejected it, every check passed, or there are
 * findings.
 */
public sealed interface Verdict {

  /** The label used in the JSONL export ({@link ExportRecord}) and in seed expectations (#320). */
  String label();

  /** The compiler rejected the template; each diagnostic names file, line and column. */
  record Rejected(List<String> diagnostics) implements Verdict {
    public Rejected {
      diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public String label() {
      return "REJECTED";
    }
  }

  /** The template compiled and no check found anything. */
  record Clean() implements Verdict {
    @Override
    public String label() {
      return "CLEAN";
    }
  }

  /** The template compiled and the checks found something. */
  record Findings(List<Finding> findings) implements Verdict {
    public Findings {
      findings = List.copyOf(findings);
      if (findings.isEmpty()) {
        throw new IllegalArgumentException("no findings: use Clean");
      }
    }

    @Override
    public String label() {
      return "FINDINGS";
    }
  }
}
