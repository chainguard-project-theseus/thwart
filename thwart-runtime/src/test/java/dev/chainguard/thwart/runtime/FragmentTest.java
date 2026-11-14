package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * #676: {@code Fragment} is compile-time only. It has no instances, so no Java code can build,
 * subclass or vouch for one; a fragment argument exists only between compiled templates.
 */
class FragmentTest {

  /** Negative: no constructor, factory or subclass outside the class, and no lambda is one. */
  @Test
  void javaCannotBuildAFragment() {
    assertFalse(Fragment.class.isInterface());
    assertTrue(Modifier.isFinal(Fragment.class.getModifiers()));
    for (Constructor<?> c : Fragment.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(c.getModifiers()), c.toString());
    }
    for (Method m : Fragment.class.getDeclaredMethods()) {
      assertFalse(Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()), m.toString());
    }
    // Body, the generated Java type of a Fragment, is not a Fragment.
    assertFalse(Fragment.class.isAssignableFrom(Fragment.Body.class));
    assertEquals(1, Fragment.class.getDeclaredClasses().length);
  }
}
