package dev.tessera.runtime;

import dev.tessera.runtime.internal.Output;

/**
 * The type of a markup argument between compiled templates, e.g. {@code ~{::body}} in
 * {@code t:replace="~{layout :: layout(~{::body}, 'home')}"}; the receiving fragment declares a
 * {@code Fragment} parameter and inserts it with {@code t:insert} or {@code t:replace}.
 *
 * <p>Compile-time only: this class has no instances, so no Java code can build or vouch for a
 * {@code Fragment}. The compiler checks each argument's markup at the call site, where the callee
 * inserts it. In generated code a {@code Fragment} parameter is a {@link Body} of a {@code private}
 * method: its class and each class that calls it get a private copy, so only the generated
 * class itself can pass one. A page ({@code t:params}) and a {@code @TemplateFunction} may not take
 * a {@code Fragment}.
 */
public final class Fragment {
  /** What a fragment argument writes; generated code only, as the Java type of a {@code Fragment}. */
  @FunctionalInterface
  public interface Body {
    void render(RenderContext ctx, Output out);
  }

  private Fragment() {}
}
