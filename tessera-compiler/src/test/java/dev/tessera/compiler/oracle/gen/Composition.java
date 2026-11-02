package dev.tessera.compiler.oracle.gen;

/**
 * One value of the composition axis (#321).
 *
 * <ul>
 *   <li>{@link #NONE}: the hole is written in place.
 *   <li>{@link #INSERT}/{@link #REPLACE}: the innermost container is a fragment {@code
 *       frag(String s)} (or {@code frag(SafeHtml h)}), inserted with {@code t:insert} on, or
 *       {@code t:replace} inside, a second container (the insertion point axis).
 *   <li>{@link #REMOVE_TAG}: {@code t:remove="tag"} on the innermost container.
 * </ul>
 *
 * <p>Raw output of a {@code SafeHtml} is a hole ({@link Hole#SAFE_HTML_INLINE}, {@link Hole#T_UTEXT}).
 */
public enum Composition {
  NONE("none"),
  INSERT("t:insert"),
  REPLACE("t:replace"),
  REMOVE_TAG("t:remove=tag");

  private final String label;

  Composition(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** Whether the case has an insertion point (the hole is in a fragment inserted elsewhere). */
  public boolean inserts() {
    return this == INSERT || this == REPLACE;
  }
}
