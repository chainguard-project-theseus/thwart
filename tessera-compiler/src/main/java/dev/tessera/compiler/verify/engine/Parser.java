package dev.tessera.compiler.verify.engine;

/**
 * One running parser. Not thread-safe. After any {@link EngineException} the parser is broken and
 * every later call throws.
 */
public interface Parser {

  /**
   * Parses {@code chunk} after everything fed so far. There is no end-of-file processing, and a
   * chunk may end anywhere: inside a tag, a character reference, a CRLF pair or a surrogate pair.
   */
  void feed(String chunk) throws EngineException;

  /** The state after the last {@link #feed}. Immutable. */
  ParserState state() throws EngineException;

  /** An independent copy in the same state: feeding one doesn't change the other. */
  Parser fork() throws EngineException;
}
