package dev.tessera.runtime;

import java.util.Objects;

/**
 * A form field passed to a fragment parameter of type {@code Field}, e.g.
 * {@code t:fragment="input(String label, Field name, String type)"} called as
 * {@code ~{fragments/inputField :: input(#{firstName}, 'firstName', 'text')}}. The argument is a
 * string literal checked at build time against the caller's {@code t:object} type; generated code
 * creates the {@code Field}. Inside the fragment {@code t:field="name"}, {@code t:errors="name"},
 * {@code t:errorclass} and {@code fields.hasErrors(name)} accept it. Output as text it is its
 * element id ({@code t:for="name"}), the path without brackets. Templates cannot read its value.
 *
 * <p>Declared with its type, {@code Field<Criterion> name}, the fragment can also bind the
 * field's properties: {@code t:field="name.answer"} is the path {@code <name>.answer}, checked
 * against {@code Criterion} at build time. A plain {@code Field} parameter is a {@code Field<?>}.
 *
 * @param <T> the field's type
 */
public final class Field<T> {
  private final String objectName;
  private final String path;
  private final T value;

  /**
   * @param objectName the {@code t:object} name (the Spring model attribute name)
   * @param path the dotted field path, e.g. {@code address.city}
   * @param value the field's current value
   */
  public Field(String objectName, String path, T value) {
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

  public T value() {
    return value;
  }

  /**
   * The element id {@code t:field} writes for this field: the path without '[' and ']', as Spring
   * and Thymeleaf write it ({@code items1.name} for {@code items[1].name}, #193).
   */
  public String id() {
    return path.replace("[", "").replace("]", "");
  }

  /** The {@link #id}, so that {@code t:for="name"} points at the input {@code t:field="name"} writes. */
  @Override
  public String toString() {
    return id();
  }
}
