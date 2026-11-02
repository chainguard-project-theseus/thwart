package dev.tessera.runtime;

/**
 * Test code only (#664): a {@code SafeHtml} of fixed markup, through {@link SafeHtml#of} of a
 * hand-written {@code Template} whose {@code verifiedFlowPartial()} vouches for it (a door
 * {@code tessera:review} fails the build on in application code).
 */
final class TestSafeHtml {
  private TestSafeHtml() {}

  static SafeHtml of(String html) {
    return SafeHtml.of(new Template() {
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
    }, RenderContext.defaults());
  }
}
