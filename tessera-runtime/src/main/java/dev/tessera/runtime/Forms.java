package dev.tessera.runtime;

import java.util.List;
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

  /** Resolved error messages for a field; {@code path} {@code "*"} means all errors. */
  List<String> errors(String objectName, String path);

  default boolean hasErrors(String objectName, String path) {
    return !errors(objectName, path).isEmpty();
  }

  /** CSRF token to insert into {@code method="post"} forms, if any. */
  Optional<CsrfToken> csrfToken();

  /** CSRF token parameter name and value. */
  record CsrfToken(String parameterName, String token) {}

  /** No binding state: values are {@link Escapers#toText}, no errors, no CSRF token. */
  Forms NONE =
      new Forms() {
        @Override
        public String value(String objectName, String path, Object currentValue) {
          return Escapers.toText(currentValue);
        }

        @Override
        public List<String> errors(String objectName, String path) {
          return List.of();
        }

        @Override
        public Optional<CsrfToken> csrfToken() {
          return Optional.empty();
        }
      };
}
