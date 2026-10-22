package dev.tessera.runtime.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StringsTest {
  @Test
  void isEmptyAndIsBlank() {
    for (Object empty : new Object[] {null, "", "  \t"}) {
      assertTrue(Strings.isEmpty(empty));
      assertTrue(Strings.isBlank(empty));
    }
    assertFalse(Strings.isEmpty(" a "));
    assertFalse(Strings.isBlank(0));
  }

  @Test
  void defaultString() {
    assertEquals("d", Strings.defaultString(null, "d"));
    assertEquals("d", Strings.defaultString(" ", "d"));
    assertEquals("x", Strings.defaultString("x", "d"));
    assertEquals("42", Strings.defaultString(42, "d"));
  }

  @Test
  void abbreviate() {
    assertEquals("abc", Strings.abbreviate("abc", 3));
    assertEquals("ab...", Strings.abbreviate("abcdefgh", 5));
    assertNull(Strings.abbreviate(null, 5));
    assertThrows(IllegalArgumentException.class, () -> Strings.abbreviate("abc", 2));
  }

  @Test
  void caseMapping() {
    assertEquals("TITLE", Strings.toUpperCase("title"));
    assertEquals("title", Strings.toLowerCase("TITLE"));
    // Locale.ROOT: no Turkish dotless i surprises.
    assertEquals("i", Strings.toLowerCase("I"));
    assertNull(Strings.toUpperCase(null));
    assertNull(Strings.toLowerCase(null));
  }

  @Test
  void capitalize() {
    assertEquals("Hello world", Strings.capitalize("hello world"));
    assertEquals("", Strings.capitalize(""));
    assertEquals("𐐀x", Strings.capitalize("𐐨x"));
    assertNull(Strings.capitalize(null));
  }

  @Test
  void trim() {
    assertEquals("a b", Strings.trim("  a b \n"));
    assertNull(Strings.trim(null));
  }

  @Test
  void containsStartsWithEndsWith() {
    assertTrue(Strings.contains("hello", "ell"));
    assertFalse(Strings.contains("hello", "xyz"));
    assertFalse(Strings.contains(null, "a"));
    assertFalse(Strings.contains("a", null));
    assertTrue(Strings.startsWith("hello", "he"));
    assertFalse(Strings.startsWith("hello", "lo"));
    assertFalse(Strings.startsWith(null, "a"));
    assertTrue(Strings.endsWith("hello", "lo"));
    assertFalse(Strings.endsWith("hello", "he"));
    assertFalse(Strings.endsWith(null, "a"));
    assertTrue(Strings.contains(1234, "23"));
  }

  @Test
  void substring() {
    assertEquals("llo", Strings.substring("hello", 2));
    assertEquals("el", Strings.substring("hello", 1, 3));
    assertNull(Strings.substring(null, 1));
    assertNull(Strings.substring(null, 1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> Strings.substring("abc", 5));
  }

  @Test
  void replace() {
    assertEquals("a-b-c", Strings.replace("a b c", " ", "-"));
    assertEquals("abc", Strings.replace("a b c", " ", null));
    assertEquals("abc", Strings.replace("abc", "", "x"));
    assertNull(Strings.replace(null, "a", "b"));
  }

  @Test
  void join() {
    assertEquals("a, b, ", Strings.join(Arrays.asList("a", "b", null), ", "));
    assertEquals("1|2", Strings.join(new Integer[] {1, 2}, "|"));
    assertEquals("x", Strings.join(Set.of("x"), ","));
    assertEquals("ab", Strings.join(List.of("a", "b"), null));
    assertNull(Strings.join(null, ","));
    assertThrows(IllegalArgumentException.class, () -> Strings.join("abc", ","));
  }
}
