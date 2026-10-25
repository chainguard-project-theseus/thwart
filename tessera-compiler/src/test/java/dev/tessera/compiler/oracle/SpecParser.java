package dev.tessera.compiler.oracle;

import org.w3c.dom.Document;

/**
 * A spec-conformant HTML parser the oracle parses renders with ({@link SpecParsers}: {@code vnu-26},
 * validator 26.10.12, current spec).
 */
public interface SpecParser {

  /** The engine name used in {@link Finding#engine()} and {@code known-holes.txt}. */
  String name();

  /**
   * Parses {@code html} as a full document without altering the tree.
   *
   * @param scripting whether the parser runs with scripting on ({@code noscript} is raw text) or off
   */
  Document parse(String html, boolean scripting);
}
