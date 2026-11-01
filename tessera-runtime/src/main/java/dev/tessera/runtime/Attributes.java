package dev.tessera.runtime;

/**
 * Attribute helpers called by generated template code. Not intended for application code. The
 * results are still escaped by {@link Escapers} when written.
 */
public final class Attributes {
  private Attributes() {}

  /**
   * {@code t:classappend}: {@code base + " " + extra}. An empty or null {@code extra} leaves
   * {@code base} unchanged (null stays null, so the attribute is omitted); an empty or null
   * {@code base} yields {@code extra} alone.
   */
  public static String appendClass(Object base, Object extra) {
    String b = Escapers.toText(base);
    String e = Escapers.toText(extra);
    if (e.isEmpty()) {
      return base == null ? null : b;
    }
    return b.isEmpty() ? e : b + " " + e;
  }
}
