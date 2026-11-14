package dev.chainguard.thwart.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.DefaultLinks;
import dev.chainguard.thwart.runtime.internal.Escapers;
import dev.chainguard.thwart.runtime.Forms;
import dev.chainguard.thwart.runtime.Messages;
import dev.chainguard.thwart.runtime.RenderContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The Owner/Address form model and {@link Forms} stub shared by FormsTest and FieldFragmentsTest. */
final class FormFixture {
  private FormFixture() {}

  /** A Forms with fixed rejected values, errors and CSRF token. */
  static final class TestForms implements Forms {
    final Map<String, String> rejected = new HashMap<>();
    final Map<String, List<String>> errors = new HashMap<>();
    CsrfToken token;

    @Override
    public String value(String objectName, String path, Object currentValue) {
      assertEquals("owner", objectName);
      return rejected.containsKey(path) ? rejected.get(path) : Escapers.toText(currentValue);
    }

    @Override
    public String selectedValue(String objectName, String path, Object currentValue) {
      assertEquals("owner", objectName);
      return rejected.containsKey(path) ? rejected.get(path) : currentValue == null ? null : Escapers.toText(currentValue);
    }

    @Override
    public List<String> errors(String objectName, String path) {
      assertEquals("owner", objectName);
      if (path.equals("*")) {
        return errors.values().stream().flatMap(List::stream).toList();
      }
      return errors.getOrDefault(path, List.of());
    }

    @Override
    public Optional<CsrfToken> csrfToken() {
      return Optional.ofNullable(token);
    }
  }

  /** A builder with the {@code com.example.Address} and {@code com.example.Owner} records. */
  static Compilation.Builder model() {
    return Compilation.builder()
        .java("com.example.Address", "package com.example; public record Address(String city) {}")
        .java(
            "com.example.Owner",
            "package com.example; public record Owner(String firstName, int age, boolean active, Address address,"
                + " String pet, String bio, String password) {}");
  }

  /** An Owner named {@code firstName}, with an Address in {@code city} (or none if null). */
  static Object owner(Compilation c, String firstName, String city) {
    Object address = city == null ? null : c.newInstance("com.example.Address", city);
    return c.newInstance("com.example.Owner", firstName, 42, true, address, "dog", "a <b>bio</b>", "secret");
  }

  static RenderContext ctx(Forms forms) {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), forms);
  }
}
