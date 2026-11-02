package dev.tessera.compiler.testing;

import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import dev.tessera.runtime.Template;

/**
 * Test code only: a {@code Fragment.of} or {@code SafeHtml.of} that writes fixed markup (#581,
 * #664). It vouches for the markup as a hand-written {@code Template} whose
 * {@code verifiedFlowPartial()} returns true does (a door {@code tessera:review} fails the build on
 * in application code), so tests use it only for markup that is a flow partial, or to probe what
 * the verifier assumes about such a value.
 */
public final class TestFragments {
  private TestFragments() {}

  public static Fragment markup(String html) {
    return Fragment.of(vouching(html));
  }

  public static SafeHtml safeHtml(String html) {
    return SafeHtml.of(vouching(html), RenderContext.defaults());
  }

  private static Template vouching(String html) {
    return new Template() {
      @Override
      public String templateName() {
        return "test";
      }

      @Override
      public void render(RenderContext ctx, Output out) {
        out.write(html);
      }

      @Override
      public boolean verifiedFlowPartial() {
        return true;
      }
    };
  }
}
