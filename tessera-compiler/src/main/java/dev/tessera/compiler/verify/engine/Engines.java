package dev.tessera.compiler.verify.engine;

/**
 * The engine the verifier runs (security-architecture.md §11.4): validator.nu htmlparser, built
 * for Java 8, renamed into {@code dev.tessera.compiler.internal.html5} and shaded into this jar
 * (#365).
 */
public final class Engines {
  private Engines() {}

  /** Current upstream: the htmlparser source in {@code nu.validator:validator:26.10.12}. */
  public static final Engine CURRENT = new VnuEngine();
}
