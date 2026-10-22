package dev.tessera.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-render services that generated code may use. This is the complete list: a template
 * cannot reach anything else (no request, session, beans or static state).
 *
 * @param locale locale for messages
 * @param messages resolves {@code #{key(args)}}
 * @param links builds {@code @{/path/{id}(id=x)}} URLs
 * @param forms form-binding state for {@code t:field}/{@code t:errors} and CSRF tokens
 */
public record RenderContext(Locale locale, Messages messages, Links links, Forms forms) {

  public RenderContext {
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(links, "links");
    Objects.requireNonNull(forms, "forms");
  }

  /** Context for rendering outside a web request: no messages, empty context path, no forms. */
  public static RenderContext defaults() {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), Forms.NONE);
  }
}
