package dev.chainguard.thwart.runtime;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
 * writes. A context also numbers the ids {@code t:field} writes on checkboxes and radio buttons
 * and {@code ids.seq/next/prev} return ({@link #sequenceId}), so use a new one for every render,
 * as the Spring integration does, and don't share or cache one: it has identity, not value,
 * equality.
 */
public final class RenderContext {
  private final Locale locale;
  private final Messages messages;
  private final Links links;
  private final Forms forms;
  private final String cspNonce;
  /** Per-render id counters ({@link #sequenceId}); not part of toString. */
  private final Map<String, Integer> idSequences = new HashMap<>();

  public RenderContext(Locale locale, Messages messages, Links links, Forms forms) {
    this(locale, messages, links, forms, null);
  }

  /**
   * A context with a Content Security Policy nonce. Generated code adds {@code nonce="<cspNonce>"}
   * (attribute-escaped) to every {@code <script>} and {@code <style>} start tag it writes, so a
   * policy such as {@code script-src 'nonce-<cspNonce>'} allows the template's own scripts and
   * nothing else. Markup the compiler did not write (a sanitized {@code SafeHtml} or a hand-written
   * {@code Template}'s output) gets no nonce.
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

  /**
   * A new context for one render outside a web request: no messages, empty context path, no forms.
   */
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

  /**
   * {@code id} followed by the number of times this context was asked for it, starting at 1:
   * {@code roles1}, {@code roles2}, ... Thymeleaf's {@code IdentifierSequences} (one per render),
   * used for the ids {@code t:field} writes on checkboxes and radio buttons.
   */
  public String sequenceId(String id) {
    synchronized (idSequences) {
      Integer previous = idSequences.get(id);
      int next = previous == null ? 1 : previous + 1;
      idSequences.put(id, next);
      return id + next;
    }
  }

  /**
   * The id the next {@link #sequenceId} call for {@code id} will return, without counting it:
   * {@code roles1} before the first. Thymeleaf's {@code IdentifierSequences.getNextIDSeq}
   * ({@code #ids.next}).
   */
  public String nextSequenceId(String id) {
    synchronized (idSequences) {
      Integer previous = idSequences.get(id);
      return id + (previous == null ? 1 : previous + 1);
    }
  }

  /**
   * The id the last {@link #sequenceId} call for {@code id} returned. Thymeleaf's
   * {@code IdentifierSequences.getPreviousIDSeq} ({@code #ids.prev}).
   *
   * @throws IllegalStateException if this context has not numbered {@code id} yet
   */
  public String previousSequenceId(String id) {
    synchronized (idSequences) {
      Integer previous = idSequences.get(id);
      if (previous == null) {
        throw new IllegalStateException("Cannot obtain previous ID count for ID \"" + id + "\"");
      }
      return id + previous;
    }
  }

  /** The Content Security Policy nonce written on every {@code <script>} and {@code <style>}. */
  public Optional<String> cspNonce() {
    return Optional.ofNullable(cspNonce);
  }

  /** The nonce itself is not included, so a logged context does not reveal it. */
  @Override
  public String toString() {
    return "RenderContext[locale=" + locale + ", messages=" + messages + ", links=" + links
        + ", forms=" + forms + (cspNonce == null ? "" : ", cspNonce=(set)") + "]";
  }
}
