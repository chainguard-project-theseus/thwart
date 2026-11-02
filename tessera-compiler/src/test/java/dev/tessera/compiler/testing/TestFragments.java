package dev.tessera.compiler.testing;

import dev.tessera.runtime.SafeHtml;
import java.lang.reflect.Constructor;

/**
 * Test code only (#664, #676): a {@code SafeHtml} of fixed markup, through its private
 * constructor. Application code has no such door; tests use it only for markup that is FLOW
 * content, or to probe what the verifier assumes about such a value.
 */
public final class TestFragments {
  private TestFragments() {}

  public static SafeHtml safeHtml(String html) {
    try {
      Constructor<SafeHtml> c = SafeHtml.class.getDeclaredConstructor(String.class);
      c.setAccessible(true);
      return c.newInstance(html);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
