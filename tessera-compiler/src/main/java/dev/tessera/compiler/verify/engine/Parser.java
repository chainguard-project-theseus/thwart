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
  default void feed(String chunk) throws EngineException {
    feed(chunk, null);
  }

  /**
   * {@link #feed(String)}, telling {@code listener} (if not null) about each parse error and each
   * element inserted while parsing {@code chunk}, once: a chunk that ends inside a token doesn't
   * repeat them when the next chunk completes it. An engine that doesn't report errors
   * ({@link Engine#reportsErrors()}) tells it nothing.
   */
  void feed(String chunk, Listener listener) throws EngineException;

  /** The state after the last {@link #feed}. Immutable. */
  ParserState state() throws EngineException;

  /** An independent copy in the same state: feeding one doesn't change the other. */
  Parser fork() throws EngineException;

  /** What a parser reports while it parses (#406). Offsets are into the chunk being fed, from 0. */
  interface Listener {
    /** A tokenizer or tree-builder parse error, in the engine's words, at the character it was reading. */
    void error(String message, int offset);

    /** An element was inserted at {@code offset}; {@code stack} may be read only during this call. */
    void pushed(Stack stack, int offset);
  }

  /** The stack of open elements when an element is inserted ({@link Listener#pushed}). */
  interface Stack {
    /** The context element of fragment parsing, or null for document parsing. */
    OpenElement context();

    /** The number of open elements, the root ({@code html}) first. */
    int size();

    String namespace(int i);

    String localName(int i);

    /** The inserted element's namespace URI. */
    String insertedNamespace();

    /** The inserted element's local name. */
    String insertedName();

    /** Whether the inserted element is the top of the stack (false for a void element). */
    boolean insertedOnStack();

    /** The template insertion modes, the outermost first. */
    int templateModeCount();

    InsertionMode templateMode(int i);
  }
}
