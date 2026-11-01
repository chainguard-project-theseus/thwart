package dev.tessera.compiler.testing;

import dev.tessera.runtime.Fragment;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.Template;

/**
 * Test code only: a {@code Fragment.of} that writes fixed markup (#581). It vouches for the markup
 * as a hand-written {@code Template} whose {@code verifiedFlowPartial()} returns true does, so
 * tests use it only for markup that is a flow partial, or to probe what the verifier assumes about
 * such a value.
 */
public final class TestFragments {
  private TestFragments() {}

  public static Fragment markup(String html) {
    return Fragment.of(new Template() {
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
    });
  }
}
