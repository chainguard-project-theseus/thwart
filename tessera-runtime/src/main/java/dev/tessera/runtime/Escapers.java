package dev.tessera.runtime;

/**
 * Contextual escapers. The compiler picks exactly one of these for every expression output
 * site based on its {@code OutputContext}; templates cannot choose. Every method treats
 * {@code null} as the empty string.
 *
 * <p>Conversion to text: {@code null} becomes {@code ""}; everything else uses
 * {@link String#valueOf(Object)}. Safe types are only honoured where noted.
 */
public final class Escapers {
  private Escapers() {}

  /** Converts a value to its display text ({@code null} becomes {@code ""}). */
  public static String toText(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * HTML element text (OutputContext.TEXT). Encodes {@code & < > " '}. A {@link SafeHtml}
   * argument is escaped like any other value: {@code t:text} always escapes.
   */
  public static void text(Output out, Object value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.text");
  }

  /** Quoted attribute value (OutputContext.ATTRIBUTE). Encodes {@code & < > " '}. */
  public static void attr(Output out, Object value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.attr");
  }

  /**
   * URL-valued attribute such as {@code href}/{@code action} (OutputContext.URL). A
   * {@link TrustedUrl} is attribute-encoded as is. Any other value is attribute-encoded after
   * a scheme check: only {@code http}, {@code https}, {@code mailto}, {@code tel} and
   * scheme-less (relative) URLs pass; anything else is replaced by {@code about:invalid#tessera}.
   */
  public static void url(Output out, Object value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.url");
  }

  /**
   * Resource URL attribute such as {@code <script src>} (OutputContext.RESOURCE_URL). Only
   * accepts compiler-vetted values: a {@link TrustedUrl}, or a String produced by a link
   * expression with a literal path. The compiler rejects every other expression.
   */
  public static void resourceUrl(Output out, Object value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.resourceUrl");
  }

  /** Raw HTML (t:utext and {@code [(...)]}). Only {@link SafeHtml} is accepted. */
  public static void html(Output out, SafeHtml value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.html");
  }

  /**
   * JavaScript value inside {@code <script>} (OutputContext.SCRIPT). Serializes as JSON:
   * null, Boolean, Number, CharSequence, Character, Enum (name), java.time values (ISO string),
   * Optional, Collection, array and Map with String keys. Escapes {@code <}, {@code >},
   * {@code &}, U+2028 and U+2029 so the value cannot close the script element. Other types
   * throw IllegalArgumentException (the compiler only allows the types above).
   */
  public static void json(Output out, Object value) {
    throw new UnsupportedOperationException("TODO(#1): Escapers.json");
  }
}
