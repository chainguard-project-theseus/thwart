package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UrlEncodingTest {
  @Test
  void pathSegmentKeepsOnlyUnreserved() {
    assertEquals("AZaz09-._~", UrlEncoding.pathSegment("AZaz09-._~"));
    assertEquals("%2F%3F%23%20%25%3A%40%26%3D%2B%3B%2C%27%22%3C%3E%5C", UrlEncoding.pathSegment("/?# %:@&=+;,'\"<>\\"));
    assertEquals("..", UrlEncoding.pathSegment(".."));
    assertEquals("%C3%BC%E6%97%A5%F0%9F%98%80", UrlEncoding.pathSegment("ü日😀"));
    assertEquals("%0D%0A%00", UrlEncoding.pathSegment("\r\n\0"));
  }

  @Test
  void queryParamEncodesDelimiters() {
    assertEquals("%26%3D%2B%23%3F%20", UrlEncoding.queryParam("&=+#? "));
    assertEquals("a/b:c@d!$'()*,;", UrlEncoding.queryParam("a/b:c@d!$'()*,;"));
    assertEquals("%25%22%3C%3E%5C%C3%A9", UrlEncoding.queryParam("%\"<>\\é"));
  }

  @Test
  void unpairedSurrogateDoesNotThrow() {
    assertEquals("%3F", UrlEncoding.pathSegment("\uD800"));
  }
}
