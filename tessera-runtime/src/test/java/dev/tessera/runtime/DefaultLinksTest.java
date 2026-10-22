package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefaultLinksTest {
  private final DefaultLinks root = new DefaultLinks("");
  private final DefaultLinks app = new DefaultLinks("/app");

  private static String[] n(String... names) {
    return names;
  }

  private static Object[] v(Object... values) {
    return values;
  }

  @Test
  void contextPathPrependedToContextRelativePaths() {
    assertEquals("/app/owners", app.build("/owners", n(), v()));
    assertEquals("/owners", root.build("/owners", n(), v()));
    assertEquals("/app", app.contextPath());
  }

  @Test
  void absoluteUrlsUntouched() {
    assertEquals("https://example.com/x", app.build("https://example.com/x", n(), v()));
    assertEquals("//cdn.example.com/a.js", app.build("//cdn.example.com/a.js", n(), v()));
    assertEquals("mailto:a@b.c", app.build("mailto:a@b.c", n(), v()));
  }

  @Test
  void pageRelativeUntouched() {
    assertEquals("edit?x=1", app.build("edit", n("x"), v(1)));
  }

  @Test
  void pathVariablesSubstitutedAndRemovedFromQuery() {
    assertEquals(
        "/app/owners/42/pets/7/edit?q=term",
        app.build("/owners/{ownerId}/pets/{petId}/edit", n("ownerId", "q", "petId"), v(42, "term", 7)));
  }

  @Test
  void pathVariableUsedTwice() {
    assertEquals("/a/1/b/1", root.build("/a/{id}/b/{id}", n("id"), v(1)));
  }

  @Test
  void unknownPlaceholderLeftAsWritten() {
    assertEquals("/a/{x}?y=1", root.build("/a/{x}", n("y"), v(1)));
  }

  @Test
  void pathVariableEncoding() {
    assertEquals("/f/a%2Fb", root.build("/f/{x}", n("x"), v("a/b")));
    assertEquals("/f/a%3Fb%3Dc", root.build("/f/{x}", n("x"), v("a?b=c")));
    assertEquals("/f/a%23b", root.build("/f/{x}", n("x"), v("a#b")));
    assertEquals("/f/a%20b", root.build("/f/{x}", n("x"), v("a b")));
    assertEquals("/f/caf%C3%A9%E2%82%AC", root.build("/f/{x}", n("x"), v("café€")));
    assertEquals("/f/..%2F..%2Fetc", root.build("/f/{x}", n("x"), v("../../etc")));
    assertEquals("/f/%5C%5Cevil.com", root.build("/f/{x}", n("x"), v("\\\\evil.com")));
    assertEquals("/f/javascript%3Aalert(1)".replace("(", "%28").replace(")", "%29"),
        root.build("/f/{x}", n("x"), v("javascript:alert(1)")));
  }

  @Test
  void encodedValueIsNotReexpanded() {
    assertEquals("/f/%7By%7D/2", root.build("/f/{x}/{y}", n("x", "y"), v("{y}", 2)));
  }

  @Test
  void dotSegmentPathVariablesRejected() {
    for (Object bad : new Object[] {"..", "."}) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> root.build("/f/{x}/edit", n("x"), v(bad)));
      assertTrue(e.getMessage().contains("'x'"), e.getMessage());
    }
  }

  @Test
  void emptyOrNullPathVariableRejected() {
    // "/{x}/evil.com" with x="" would otherwise become the protocol-relative "//evil.com".
    assertThrows(IllegalArgumentException.class, () -> root.build("/{x}/evil.com", n("x"), v("")));
    assertThrows(IllegalArgumentException.class, () -> root.build("/{x}/evil.com", n("x"), v((Object) null)));
  }

  @Test
  void queryParamsInDeclarationOrderWithNullsAsBareNames() {
    assertEquals("/s?z=1&a&m=x", root.build("/s", n("z", "a", "m"), v(1, null, "x")));
  }

  @Test
  void queryParamEncoding() {
    assertEquals(
        "/s?q=a%26b%3Dc%2Bd%23e%3Ff%20g&k%26=%C3%A9",
        root.build("/s", n("q", "k&"), v("a&b=c+d#e?f g", "é")));
  }

  @Test
  void queryAppendedToExistingQueryAndBeforeFragment() {
    assertEquals("/app/s?a=1&b=2#top", app.build("/s?a=1#top", n("b"), v(2)));
    assertEquals("/s?b=2#top", root.build("/s#top", n("b"), v(2)));
  }

  @Test
  void mismatchedArraysRejected() {
    assertThrows(IllegalArgumentException.class, () -> root.build("/s", n("a"), v()));
  }
}
