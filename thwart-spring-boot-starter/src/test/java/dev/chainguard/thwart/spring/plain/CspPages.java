package dev.chainguard.thwart.spring.plain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.spring.CspNonce;
import java.util.Base64;
import org.springframework.test.web.servlet.MvcResult;

/** Checks on the {@code /csp} page (#335), shared by the Boot and plain Spring tests. */
public final class CspPages {
  private CspPages() {}

  /**
   * Asserts that the style and both scripts of {@code smoke/csp} carry the request's nonce, a
   * fresh 128-bit base64 value, and that nothing else does. Returns the nonce.
   */
  public static String assertStamped(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    Object nonce = result.getRequest().getAttribute(CspNonce.ATTRIBUTE);
    assertTrue(nonce instanceof String, "request attribute " + CspNonce.ATTRIBUTE + ": " + nonce);
    assertEquals(16, Base64.getDecoder().decode((String) nonce).length, (String) nonce);
    assertTrue(body.contains("<style nonce=\"" + nonce + "\">p { color: green }</style>"), body);
    assertTrue(body.contains("<script src=\"/app.js\" nonce=\"" + nonce + "\"></script>"), body);
    assertTrue(body.contains("<script nonce=\"" + nonce + "\">var ok = true;</script>"), body);
    assertEquals(3, body.split("nonce=", -1).length - 1, body);
    return (String) nonce;
  }
}
