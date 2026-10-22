package dev.tessera.runtime;

/**
 * Character sink for rendered markup. Everything written here is emitted verbatim, so only
 * generated code (for static template text) and {@link Escapers} may call it. Never pass
 * user data to {@code write} directly.
 */
public interface Output {

  void write(String s);

  void write(char c);
}
