package dev.chainguard.thwart.runtime;

import java.lang.reflect.Constructor;

/**
 * Test code only (#664, #676): a {@code SafeHtml} of fixed markup, through the private constructor.
 * Application code has no such door.
 */
public final class TestSafeHtml {
  private TestSafeHtml() {}

  public static SafeHtml of(String html) {
    try {
      Constructor<SafeHtml> c = SafeHtml.class.getDeclaredConstructor(String.class);
      c.setAccessible(true);
      return c.newInstance(html);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
