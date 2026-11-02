package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The context path and encodeURL hook; link building itself is compiled (LinkCompilerTest). */
class DefaultLinksTest {
  /** #358: a context path ending in '/' must not turn /a into the protocol-relative //a. */
  @Test
  void trailingSlashInContextPathIsDropped() {
    assertEquals("", new DefaultLinks("").contextPath());
    assertEquals("", new DefaultLinks("/").contextPath());
    assertEquals("", new DefaultLinks("//").contextPath());
    assertEquals("/app", new DefaultLinks("/app").contextPath());
    assertEquals("/app", new DefaultLinks("/app/").contextPath());
    assertEquals("/app", new DefaultLinks("/app//").contextPath());
  }

  /** Negative (#358): a context path that is not a plain single-slash path is rejected. */
  @Test
  void contextPathThatIsNotAPlainPathIsRejected() {
    for (String bad :
        new String[] {
          "//evil.example", "//evil.example/", "/\\evil.example", "\\evil.example", "app", " /app", "/a pp",
          "/app\t", "/app?x", "/app#x", "https://evil.example", "/\u007f"
        }) {
      assertThrows(IllegalArgumentException.class, () -> new DefaultLinks(bad), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> new DefaultLinks(null));
  }

  @Test
  void encodeIsTheIdentityOrTheGivenHook() {
    assertEquals("/a?b", new DefaultLinks("").encode("/a?b"));
    assertEquals("/a;jsessionid=1", new DefaultLinks("", u -> u + ";jsessionid=1").encode("/a"));
  }
}
