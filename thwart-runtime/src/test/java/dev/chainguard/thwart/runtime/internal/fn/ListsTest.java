package dev.chainguard.thwart.runtime.internal.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ListsTest {
  @Test
  void size() {
    assertEquals(3, Lists.size(List.of(1, 2, 3)));
    assertEquals(2, Lists.size(new String[] {"a", "b"}));
    assertEquals(0, Lists.size(null));
    // An Iterable that is not a Collection.
    assertEquals(2, Lists.size(Path.of("a/b")));
  }

  @Test
  void isEmpty() {
    assertTrue(Lists.isEmpty(null));
    assertTrue(Lists.isEmpty(List.of()));
    assertTrue(Lists.isEmpty(new Object[0]));
    assertFalse(Lists.isEmpty(Set.of(1)));
    assertFalse(Lists.isEmpty(Path.of("a")));
  }

  @Test
  void contains() {
    assertTrue(Lists.contains(List.of("a", "b"), "b"));
    assertFalse(Lists.contains(List.of("a", "b"), "c"));
    assertTrue(Lists.contains(Arrays.asList("a", null), null));
    assertTrue(Lists.contains(new Integer[] {1, 2}, 2));
    assertFalse(Lists.contains(null, "a"));
    assertTrue(Lists.contains(Path.of("a/b"), Path.of("b")));
  }

  @Test
  void nonListsRejected() {
    assertThrows(IllegalArgumentException.class, () -> Lists.size("abc"));
    assertThrows(IllegalArgumentException.class, () -> Lists.isEmpty(42));
    assertThrows(IllegalArgumentException.class, () -> Lists.contains("abc", "a"));
  }
}
