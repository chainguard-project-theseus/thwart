package dev.tessera.compiler.verify.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two engines the verifier runs (security-architecture.md §11.4). Both are validator.nu
 * htmlparser, built for Java 8, renamed into {@code dev.tessera.compiler.internal.html5} and
 * shaded into this jar (#365).
 */
public final class Engines {
  private Engines() {}

  /** Current upstream: the htmlparser source in {@code nu.validator:validator:26.10.12}. */
  public static final Engine CURRENT = new CurrentEngine();

  /** {@code nu.validator:htmlparser:1.4.16}: classic {@code <select>} parsing, as in Firefox ESR. */
  public static final Engine CLASSIC = new ClassicEngine();

  private static final List<Engine> ALL = Collections.unmodifiableList(Arrays.asList(CURRENT, CLASSIC));

  /** Both engines, {@link #CURRENT} first. */
  public static List<Engine> all() {
    return ALL;
  }
}
