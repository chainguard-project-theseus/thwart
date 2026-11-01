package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * #581: {@code Fragment} is a final class with no public constructor, so every value is generated
 * code ({@link Fragment#generated}) or a {@link Fragment#of} of a verified {@code FLOW} partial, and
 * only the latter carries the mark {@link Fragment#requireVerified} checks where Java hands a
 * template a {@code Fragment}.
 */
class FragmentTest {

  /** Negative: nothing outside the class can subclass it or turn a lambda into one. */
  @Test
  void fragmentIsNotImplementable() {
    assertFalse(Fragment.class.isInterface());
    assertTrue(Modifier.isFinal(Fragment.class.getModifiers()));
    for (Constructor<?> c : Fragment.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(c.getModifiers()), c.toString());
    }
  }

  /** Positive: a {@code Fragment.of} value renders the template and passes the check. */
  @Test
  void fragmentOfAVerifiedPartialPassesTheCheck() {
    Fragment f = Fragment.of(template(true));
    StringOutput out = new StringOutput();
    f.render(null, out);
    assertEquals("<p>hi</p>", out.toString());
    Fragment.requireVerified(f, "page", "content");
    Fragment.requireVerified(null, "page", "content");
  }

  /** Negative: a template that isn't a verified FLOW partial is refused, as before. */
  @Test
  void fragmentOfAnUnverifiedTemplateIsRefused() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Fragment.of(template(false)));
    assertTrue(e.getMessage().contains("Fragment.of"), e.getMessage());
  }

  /** Negative: a generated value renders, but is refused where Java hands it to a template. */
  @Test
  void generatedFragmentIsRefusedWhereJavaHandsItIn() {
    Fragment f = Fragment.generated((ctx, out) -> out.write("</form><form action=\"https://evil\">"));
    StringOutput out = new StringOutput();
    f.render(null, out);
    assertEquals("</form><form action=\"https://evil\">", out.toString());
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Fragment.requireVerified(f, "users/edit", "content"));
    assertTrue(
        e.getMessage().contains("'users/edit'") && e.getMessage().contains("'content'")
            && e.getMessage().contains("Fragment.of(template)"),
        e.getMessage());
  }

  @Test
  void generatedNeedsABody() {
    assertThrows(NullPointerException.class, () -> Fragment.generated(null));
  }

  private static Template template(boolean flow) {
    return new Template() {
      @Override
      public String templateName() {
        return "t";
      }

      @Override
      public void render(RenderContext ctx, Output out) {
        out.write("<p>hi</p>");
      }

      @Override
      public boolean verifiedFlowPartial() {
        return flow;
      }
    };
  }
}
