package dev.tessera.compiler.verify.engine;

/**
 * A spec HTML parser the build-time verifier runs (security-architecture.md §11.4): {@link
 * Engines#CURRENT}. Its parsers report parse errors and inserted elements to a {@link
 * Parser.Listener} (#406).
 */
public interface Engine {

  /** The parser and its version, for messages: {@code htmlparser@validator-26.10.12}. */
  String name();

  /** Document parsing from the initial insertion mode. */
  Parser document(boolean scripting) throws EngineException;

  /**
   * Fragment parsing (as for {@code innerHTML}) in the context of an element.
   *
   * @param contextNamespace the HTML, SVG or MathML namespace URI
   * @param contextName the context element's local name, lower case
   */
  Parser fragment(String contextNamespace, String contextName, boolean scripting) throws EngineException;
}
