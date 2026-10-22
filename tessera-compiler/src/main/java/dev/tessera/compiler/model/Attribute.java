package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;

/**
 * An attribute as written.
 *
 * @param name attribute name as written, e.g. {@code class}, {@code t:text}
 * @param value value with HTML character references decoded (what expressions are parsed
 *     from), or {@code null} for a value-less attribute such as {@code disabled}
 * @param rawValue value exactly as written between the quotes, or {@code null}
 * @param quote {@code '"'}, {@code '\''}, or {@code 0} when unquoted or value-less
 * @param pos position of the attribute name
 * @param valuePos position of the first character of the value inside the quotes, or
 *     {@code null}
 */
public record Attribute(
    String name, String value, String rawValue, char quote, SourcePos pos, SourcePos valuePos) {

  public static final String PREFIX = "t:";

  public boolean isTessera() {
    return name.startsWith(PREFIX);
  }

  /** Name without the {@code t:} prefix, e.g. {@code text} for {@code t:text}. */
  public String localName() {
    return isTessera() ? name.substring(PREFIX.length()) : name;
  }
}
