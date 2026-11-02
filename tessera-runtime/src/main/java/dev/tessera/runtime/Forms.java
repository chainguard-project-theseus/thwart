package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Form-binding state for {@code t:object}/{@code t:field}/{@code t:errors}. {@code objectName}
 * is the name of the template parameter used in {@code t:object} (which is also the Spring
 * model attribute name); {@code path} is the dotted field path, e.g. {@code address.city}.
 */
public interface Forms {

  /**
   * Display value of a bound field: after a failed bind the rejected value, otherwise
   * {@code currentValue} formatted for display. Never null.
   */
  String value(String objectName, String path, Object currentValue);

  /**
   * Value a {@code <select t:field>} compares its options with: {@link #value}, but null when the
   * bound value (after a failed bind the rejected value, otherwise {@code currentValue}) is null,
   * so that no option is selected, as with Spring's {@code SelectedValueComparator}.
   */
  default String selectedValue(String objectName, String path, Object currentValue) {
    return currentValue == null ? null : value(objectName, path, currentValue);
  }

  /**
   * Whether a checkbox, radio button or option of a {@code t:field} with value {@code candidate}
   * is checked or selected, as Thymeleaf's {@code SpringSelectedValueComparator} (and Spring's
   * {@code SelectedValueComparator}) decide: {@code candidate} equals the {@link #selectedValue},
   * or {@code currentValue} is a Collection, an array or a Map one of whose elements (keys) has the
   * display text {@code candidate}, or {@code currentValue} itself has it. An enum's display text
   * is its name or its {@code toString()}. A null {@code currentValue} matches only through the
   * {@link #selectedValue} (after a failed bind, the rejected value).
   */
  default boolean selected(String objectName, String path, Object currentValue, String candidate) {
    if (candidate.equals(selectedValue(objectName, path, currentValue))) {
      return true;
    }
    if (currentValue == null) {
      return false;
    }
    List<Object> values =
        new ArrayList<>(
            currentValue instanceof Collection
                ? (Collection<?>) currentValue
                : currentValue instanceof Map
                    ? ((Map<?, ?>) currentValue).keySet()
                    : currentValue instanceof Object[]
                        ? Arrays.asList((Object[]) currentValue)
                        : Collections.emptyList());
    values.add(currentValue);
    for (Object v : values) {
      boolean matches =
          v instanceof Enum
              ? ((Enum<?>) v).name().equals(candidate) || v.toString().equals(candidate)
              : Escapers.toText(v).equals(candidate);
      if (matches) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolved error messages for a field; {@code path} {@code "*"} means all errors and {@code
   * "global"} the form object's own (object-level) errors, without its fields'.
   */
  List<String> errors(String objectName, String path);

  default boolean hasErrors(String objectName, String path) {
    return !errors(objectName, path).isEmpty();
  }

  /** CSRF token to insert into a form with a static {@code method="post"} that posts to the page's origin, if any. */
  Optional<CsrfToken> csrfToken();

  /** CSRF token parameter name and value. */
  final class CsrfToken {
    private final String parameterName;
    private final String token;

    public CsrfToken(String parameterName, String token) {
      this.parameterName = parameterName;
      this.token = token;
    }

    public String parameterName() {
      return parameterName;
    }

    public String token() {
      return token;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CsrfToken)) {
        return false;
      }
      CsrfToken other = (CsrfToken) o;
      return Objects.equals(parameterName, other.parameterName) && Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
      return Objects.hash(parameterName, token);
    }

    @Override
    public String toString() {
      return "CsrfToken[parameterName=" + parameterName + ", token=" + token + "]";
    }
  }

  /** No binding state: values are {@link Escapers#toText}, no errors, no CSRF token. */
  Forms NONE =
      new Forms() {
        @Override
        public String value(String objectName, String path, Object currentValue) {
          return Escapers.toText(currentValue);
        }

        @Override
        public List<String> errors(String objectName, String path) {
          return Collections.emptyList();
        }

        @Override
        public Optional<CsrfToken> csrfToken() {
          return Optional.empty();
        }
      };

  /**
   * Whether {@code key} can be a map key segment of a field path, {@code prices[key]}:
   * non-null, non-empty, and without '[', ']' or quotes (' and "). Spring's property paths have no escape,
   * so such a key would end the segment early or have its quotes stripped and bind another path.
   */
  static boolean isPathKey(String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '[' || c == ']' || c == '\'' || c == '"') {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code key} for the map key segment of a field path, checked by {@link #isPathKey}; generated
   * code calls it for a key held in a variable. Throws {@code IllegalArgumentException} for a key
   * that cannot be bound.
   */
  static String pathKey(String key) {
    if (!isPathKey(key)) {
      throw new IllegalArgumentException(
          "map key " + (key == null ? "null" : "'" + key + "'") + " cannot be bound in a form field path: a key must be"
              + " non-empty and contain no '[', ']' or quotes");
    }
    return key;
  }
}
