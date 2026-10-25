package dev.tessera.compiler.verify.engine;

/**
 * An engine failed: it threw, or reached a state Tessera has no name for. The verifier turns it
 * into a build error (fail closed). A parser that threw is unusable afterwards.
 */
public final class EngineException extends Exception {
  private static final long serialVersionUID = 1L;

  public EngineException(String message) {
    super(message);
  }

  public EngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
