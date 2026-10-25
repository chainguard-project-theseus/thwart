package dev.tessera.compiler.verify.engine;

/**
 * A spec HTML parser the build-time verifier runs (security-architecture.md §11.4). The two
 * engines are {@link Engines#CURRENT} and {@link Engines#CLASSIC}; a template is accepted only if
 * both accept it.
 */
public interface Engine {

  /** The parser and its version, for messages: {@code htmlparser@validator-26.10.12}, {@code htmlparser-1.4.16}. */
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
