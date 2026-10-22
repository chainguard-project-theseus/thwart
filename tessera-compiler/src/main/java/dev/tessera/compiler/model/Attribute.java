package dev.tessera.compiler.model;

import dev.tessera.compiler.SourcePos;
import java.util.Objects;

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
public final class Attribute {
  private final String name;
  private final String value;
  private final String rawValue;
  private final char quote;
  private final SourcePos pos;
  private final SourcePos valuePos;

  public Attribute(
      String name, String value, String rawValue, char quote, SourcePos pos, SourcePos valuePos) {
    this.name = name;
    this.value = value;
    this.rawValue = rawValue;
    this.quote = quote;
    this.pos = pos;
    this.valuePos = valuePos;
  }

  public String name() {
    return name;
  }

  public String value() {
    return value;
  }

  public String rawValue() {
    return rawValue;
  }

  public char quote() {
    return quote;
  }

  public SourcePos pos() {
    return pos;
  }

  public SourcePos valuePos() {
    return valuePos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Attribute)) {
      return false;
    }
    Attribute that = (Attribute) o;
    return Objects.equals(name, that.name)
        && Objects.equals(value, that.value)
        && Objects.equals(rawValue, that.rawValue)
        && quote == that.quote
        && Objects.equals(pos, that.pos)
        && Objects.equals(valuePos, that.valuePos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(value);
    h = 31 * h + Objects.hashCode(rawValue);
    h = 31 * h + Character.hashCode(quote);
    h = 31 * h + Objects.hashCode(pos);
    h = 31 * h + Objects.hashCode(valuePos);
    return h;
  }

  @Override
  public String toString() {
    return "Attribute[name=" + name + ", value=" + value + ", rawValue=" + rawValue + ", quote=" + quote
        + ", pos=" + pos + ", valuePos=" + valuePos + "]";
  }

  public static final String PREFIX = "t:";

  public boolean isTessera() {
    return name.startsWith(PREFIX);
  }

  /** Name without the {@code t:} prefix, e.g. {@code text} for {@code t:text}. */
  public String localName() {
    return isTessera() ? name.substring(PREFIX.length()) : name;
  }

  /** The value as written, including the leading '=' and any quotes; empty for a value-less attribute. */
  public String sourceValue() {
    if (rawValue == null) {
      return "";
    }
    if (quote == 0) {
      return "=" + rawValue;
    }
    return "=" + quote + rawValue + quote;
  }
}
