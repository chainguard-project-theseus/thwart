package dev.tessera.spring;

import jakarta.servlet.ServletRequest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The request's Content Security Policy nonce. The starter's {@code RenderContext} carries
 * it, so every {@code <script>} and {@code <style>} a template writes gets {@code nonce="..."}.
 * A Tessera page whose response has no {@code Content-Security-Policy} header when it commits gets
 * {@code script-src 'nonce-N' 'strict-dynamic'; object-src 'none'; base-uri 'none'} with this
 * nonce ({@link dev.tessera.spring.view.CspFilter}; {@code tessera.csp.enabled=false} turns it
 * off). A policy the application or Spring Security sets is kept.
 *
 * <p>The nonce is created on first use, from 128 bits of {@link SecureRandom}, and kept in the
 * request attribute {@link #ATTRIBUTE}, so a header writer that runs before or after the view
 * sees the same value as the page. To supply your own, set that attribute to a non-empty String
 * before the view renders.
 */
public final class CspNonce {
  /** The request attribute holding the nonce. */
  public static final String ATTRIBUTE = CspNonce.class.getName();

  private static final SecureRandom RANDOM = new SecureRandom();

  private CspNonce() {}

  /** The request's nonce, created and stored in {@link #ATTRIBUTE} if the request has none. */
  public static String get(ServletRequest request) {
    Object existing = request.getAttribute(ATTRIBUTE);
    if (existing instanceof String && !((String) existing).isEmpty()) {
      return (String) existing;
    }
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getEncoder().encodeToString(bytes);
    request.setAttribute(ATTRIBUTE, nonce);
    return nonce;
  }
}
