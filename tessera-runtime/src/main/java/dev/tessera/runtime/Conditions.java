package dev.tessera.runtime;

/** Thymeleaf-compatible truthiness for {@code t:if}/{@code t:unless} on non-boolean values. */
public final class Conditions {
  private Conditions() {}

  /**
   * {@code null} is false; Boolean is its value; Number is {@code != 0}; Character is
   * {@code != '\0'}; a String is false only for {@code "false"}, {@code "off"}, {@code "no"}
   * (case-insensitive); anything else non-null is true.
   */
  public static boolean isTrue(Object value) {
    throw new UnsupportedOperationException("TODO(#4): Conditions.isTrue");
  }
}
