package dev.tessera.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-render services that generated code may use. This is the complete list: a template
 * cannot reach anything else (no request, session, beans or static state).
 *
 * <p>Components: {@code locale} for messages; {@code messages} resolves {@code #{key(args)}};
 * {@code links} builds {@code @{/path/{id}(id=x)}} URLs; {@code forms} is the form-binding state
 * for {@code t:field}/{@code t:errors} and CSRF tokens.
 */
public final class RenderContext {
  private final Locale locale;
  private final Messages messages;
  private final Links links;
  private final Forms forms;

  public RenderContext(Locale locale, Messages messages, Links links, Forms forms) {
    this.locale = Objects.requireNonNull(locale, "locale");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.links = Objects.requireNonNull(links, "links");
    this.forms = Objects.requireNonNull(forms, "forms");
  }

  /** Context for rendering outside a web request: no messages, empty context path, no forms. */
  public static RenderContext defaults() {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), Forms.NONE);
  }

  public Locale locale() {
    return locale;
  }

  public Messages messages() {
    return messages;
  }

  public Links links() {
    return links;
  }

  public Forms forms() {
    return forms;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof RenderContext)) {
      return false;
    }
    RenderContext other = (RenderContext) o;
    return locale.equals(other.locale)
        && messages.equals(other.messages)
        && links.equals(other.links)
        && forms.equals(other.forms);
  }

  @Override
  public int hashCode() {
    return Objects.hash(locale, messages, links, forms);
  }

  @Override
  public String toString() {
    return "RenderContext[locale=" + locale + ", messages=" + messages + ", links=" + links
        + ", forms=" + forms + "]";
  }
}
