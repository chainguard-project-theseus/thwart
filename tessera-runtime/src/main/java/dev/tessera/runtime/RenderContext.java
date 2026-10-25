package dev.tessera.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-render services that generated code may use. This is the complete list: a template
 * cannot reach anything else (no request, session, beans or static state).
 *
 * <p>Components: {@code locale} for messages; {@code messages} resolves {@code #{key(args)}};
 * {@code links} builds {@code @{/path/{id}(id=x)}} URLs; {@code forms} is the form-binding state
 * for {@code t:field}/{@code t:errors} and CSRF tokens; {@code cspNonce}, if set, is written as
 * {@code nonce="..."} on every {@code <script>} and {@code <style>} start tag the template
 * writes.
 */
public final class RenderContext {
  private final Locale locale;
  private final Messages messages;
  private final Links links;
  private final Forms forms;
  private final String cspNonce;

  public RenderContext(Locale locale, Messages messages, Links links, Forms forms) {
    this(locale, messages, links, forms, null);
  }

  /**
   * A context with a Content Security Policy nonce. Generated code adds {@code nonce="<cspNonce>"}
   * (attribute-escaped) to every {@code <script>} and {@code <style>} start tag it writes, so a
   * policy such as {@code script-src 'nonce-<cspNonce>'} allows the template's own scripts and
   * nothing else. Markup the compiler did not write (a sanitized or unchecked {@code SafeHtml})
   * gets no nonce.
   *
   * <p>Use a fresh, unguessable value for every response (at least 128 random bits, base64), and
   * the same value in the response's {@code Content-Security-Policy} header.
   *
   * @param cspNonce the nonce, or {@code null} for none
   * @throws IllegalArgumentException if {@code cspNonce} is empty
   */
  public RenderContext(Locale locale, Messages messages, Links links, Forms forms, String cspNonce) {
    this.locale = Objects.requireNonNull(locale, "locale");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.links = Objects.requireNonNull(links, "links");
    this.forms = Objects.requireNonNull(forms, "forms");
    if (cspNonce != null && cspNonce.isEmpty()) {
      throw new IllegalArgumentException("cspNonce is empty: pass null for no nonce");
    }
    this.cspNonce = cspNonce;
  }

  /** Context for rendering outside a web request: no messages, empty context path, no forms. */
  public static RenderContext defaults() {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), Forms.NONE);
  }

  /** This context with {@code cspNonce} as its nonce ({@code null} for none). */
  public RenderContext withCspNonce(String cspNonce) {
    return new RenderContext(locale, messages, links, forms, cspNonce);
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

  /** The Content Security Policy nonce written on every {@code <script>} and {@code <style>}. */
  public Optional<String> cspNonce() {
    return Optional.ofNullable(cspNonce);
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
        && forms.equals(other.forms)
        && Objects.equals(cspNonce, other.cspNonce);
  }

  @Override
  public int hashCode() {
    return Objects.hash(locale, messages, links, forms, cspNonce);
  }

  /** The nonce itself is not included, so a logged context does not reveal it. */
  @Override
  public String toString() {
    return "RenderContext[locale=" + locale + ", messages=" + messages + ", links=" + links
        + ", forms=" + forms + (cspNonce == null ? "" : ", cspNonce=(set)") + "]";
  }
}
