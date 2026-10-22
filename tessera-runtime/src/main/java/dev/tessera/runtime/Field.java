package dev.tessera.runtime;

import java.util.Objects;

/**
 * A form field passed to a fragment parameter of type {@code Field}, e.g.
 * {@code t:fragment="input(String label, Field name, String type)"} called as
 * {@code ~{fragments/inputField :: input(#{firstName}, 'firstName', 'text')}}. The argument is a
 * string literal checked at build time against the caller's {@code t:object} type; generated code
 * creates the {@code Field}. Inside the fragment {@code t:field="name"}, {@code t:errors="name"},
 * {@code t:errorclass} and {@code fields.hasErrors(name)} accept it. Output as text it is its
 * path ({@code t:for="name"}). Templates cannot read its value.
 */
public final class Field {
  private final String objectName;
  private final String path;
  private final Object value;

  /**
   * @param objectName the {@code t:object} name (the Spring model attribute name)
   * @param path the dotted field path, e.g. {@code address.city}
   * @param value the field's current value
   */
  public Field(String objectName, String path, Object value) {
    this.objectName = Objects.requireNonNull(objectName, "objectName");
    this.path = Objects.requireNonNull(path, "path");
    this.value = value;
  }

  public String objectName() {
    return objectName;
  }

  public String path() {
    return path;
  }

  public Object value() {
    return value;
  }

  /** The path, so that {@code t:for="name"} writes the field's id. */
  @Override
  public String toString() {
    return path;
  }
}
