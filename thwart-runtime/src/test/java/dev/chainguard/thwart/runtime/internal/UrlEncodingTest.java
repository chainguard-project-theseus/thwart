package dev.chainguard.thwart.runtime.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    assertEquals("%26%3D%2B%23%20", UrlEncoding.queryParam("&=+# "));
    // As in Thymeleaf: after the query's first '?', '/' and '?' end nothing (RFC 3986 3.4).
    assertEquals("a/b?c:d@e!$'()*,;", UrlEncoding.queryParam("a/b?c:d@e!$'()*,;"));
    assertEquals("/a?b%3D1%26c%232", UrlEncoding.queryParam("/a?b=1&c#2"));
    assertEquals("%25%22%3C%3E%5C%C3%A9", UrlEncoding.queryParam("%\"<>\\é"));
  }

  @Test
  void valueWithNothingToEncodeIsReturnedAsIs() {
    String s = "owner-42";
    assertSame(s, UrlEncoding.pathSegment(s));
    assertSame(s, UrlEncoding.queryParam(s));
  }

  @Test
  void unpairedSurrogateDoesNotThrow() {
    assertEquals("%3F", UrlEncoding.pathSegment("\uD800"));
  }

  @Test
  void pathVariableEncoding() {
    assertEquals("a%2Fb", UrlEncoding.pathVariable("x", "a/b"));
    assertEquals("a%3Fb%3Dc", UrlEncoding.pathVariable("x", "a?b=c"));
    assertEquals("a%23b", UrlEncoding.pathVariable("x", "a#b"));
    assertEquals("caf%C3%A9%E2%82%AC", UrlEncoding.pathVariable("x", "café€"));
    assertEquals("..%2F..%2Fetc", UrlEncoding.pathVariable("x", "../../etc"));
    assertEquals("%5C%5Cevil.com", UrlEncoding.pathVariable("x", "\\\\evil.com"));
    assertEquals("javascript%3Aalert%281%29", UrlEncoding.pathVariable("x", "javascript:alert(1)"));
    assertEquals("%7By%7D", UrlEncoding.pathVariable("x", "{y}"));
    assertEquals("42", UrlEncoding.pathVariable("x", 42L));
  }

  /** "/{x}/evil.com" with x="" would otherwise become the protocol-relative "//evil.com". */
  @Test
  void nullEmptyAndDotSegmentPathVariablesRejected() {
    for (Object bad : new Object[] {null, "", ".", ".."}) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> UrlEncoding.pathVariable("x", bad));
      assertTrue(e.getMessage().contains("'x'"), e.getMessage());
      assertThrows(IllegalArgumentException.class, () -> UrlEncoding.pathVariables("x", bad));
    }
  }

  @Test
  void queryValueNullIsEmpty() {
    assertEquals("", UrlEncoding.queryValue(null));
    assertEquals("a%26b%3Dc%2Bd%23e?f%20g", UrlEncoding.queryValue("a&b=c+d#e?f g"));
    assertEquals("&q=", UrlEncoding.queryValues("q", null));
    assertEquals("&q=1", UrlEncoding.queryValues("q", 1));
  }

  @Test
  void firstQueryTurnsTheFirstAmpersandIntoAQuestionMark() {
    assertEquals("", UrlEncoding.firstQuery(""));
    assertEquals("?a=1&b", UrlEncoding.firstQuery("&a=1&b"));
  }

  @Test
  void collectionValuesRepeatTheName() {
    assertEquals("&t=a&t&t=b%26c%3Dd", UrlEncoding.queryValues("t", Arrays.asList("a", null, "b&c=d")));
    assertEquals("&t=1&t=2", UrlEncoding.queryValues("t", new int[] {1, 2}));
    assertEquals("&t=a&t", UrlEncoding.queryValues("t", new String[] {"a", null}));
    assertEquals("", UrlEncoding.queryValues("t", Collections.emptyList()));
  }

  @Test
  void everyArrayKindSupportsQueryAndPathValues() {
    Object[] arrays = {
      new Object[] {"1", "2"}, new int[] {1, 2}, new long[] {1, 2},
      new short[] {1, 2}, new byte[] {1, 2}, new char[] {'1', '2'},
      new double[] {1, 2}, new float[] {1, 2}, new boolean[] {true, false}
    };
    for (int i = 0; i < arrays.length; i++) {
      String first = i == 8 ? "true" : i >= 6 ? "1.0" : "1";
      String second = i == 8 ? "false" : i >= 6 ? "2.0" : "2";
      assertEquals("&k%26%3D=" + first + "&k%26%3D=" + second, UrlEncoding.queryValues("k%26%3D", arrays[i]));
      assertEquals(first + "," + second, UrlEncoding.pathVariables("x", arrays[i]));
    }
  }

  @Test
  void emptyArraysEmitNoQueryAndCannotBecomeEmptyPathSegments() {
    Object[] arrays = {
      new Object[0], new int[0], new long[0], new short[0], new byte[0],
      new char[0], new double[0], new float[0], new boolean[0], Collections.emptyList()
    };
    for (Object array : arrays) {
      assertEquals("", UrlEncoding.queryValues("x", array));
      assertThrows(IllegalArgumentException.class, () -> UrlEncoding.pathVariables("x", array));
    }
    Iterable<String> empty = () -> Collections.<String>emptyList().iterator();
    assertEquals("", UrlEncoding.queryValues("x", empty));
  }

  @Test
  void nullAndEmptyElementsRetainTheirDifferentMeanings() {
    List<Object> items = Arrays.asList(null, "", "a&b");
    assertEquals("&k%26&k%26=&k%26=a%26b", UrlEncoding.queryValues("k%26", items));
    assertEquals(",,a%26b", UrlEncoding.pathVariables("x", items));
    assertEquals(",", UrlEncoding.pathVariables("x", Arrays.asList(null, null)));
  }

  @Test
  void multiValuePathEncodingCannotIntroduceUrlStructureOrDotSegments() {
    for (Object bad : new Object[] {new char[] {'.'}, new String[] {".."}, new String[] {null},
        Collections.singletonList(""), Collections.singletonList(null), Arrays.asList("..")}) {
      assertThrows(IllegalArgumentException.class, () -> UrlEncoding.pathVariables("x", bad));
    }
    assertEquals("%252e%252e,%2F%2Fevil.com,%3Fq%3Dx%23f,%5C%5Cevil.com,%2C,%00",
        UrlEncoding.pathVariables("x", Arrays.asList("%2e%2e", "//evil.com", "?q=x#f", "\\\\evil.com", ",", "\u0000")));
    // Each element is encoded on its own: a dot segment among others is part of one segment.
    assertEquals("..,a", UrlEncoding.pathVariables("x", Arrays.asList("..", "a")));
    assertEquals("a%2C,b", UrlEncoding.pathVariables("x", Arrays.asList("a,", "b")));
  }

  @Test
  void singleUseIterablesConvertEachElementBeforeAdvancing() {
    for (boolean path : new boolean[] {false, true}) {
      AtomicInteger iteratorCalls = new AtomicInteger();
      StringBuilder events = new StringBuilder();
      Iterable<Object> values = () -> {
        assertEquals(1, iteratorCalls.incrementAndGet());
        return new Iterator<Object>() {
          int next;

          public boolean hasNext() {
            return next < 2;
          }

          public Object next() {
            int index = next++;
            events.append("next").append(index).append(';');
            return new Object() {
              public String toString() {
                events.append("text").append(index).append(';');
                return "a/" + index;
              }
            };
          }
        };
      };
      assertEquals(path ? "a%2F0,a%2F1" : "&x=a/0&x=a/1",
          path ? UrlEncoding.pathVariables("x", values) : UrlEncoding.queryValues("x", values));
      assertEquals("next0;text0;next1;text1;", events.toString());
    }
  }

  @Test
  void objectArraysAreReadAsEachElementIsRendered() {
    for (boolean path : new boolean[] {false, true}) {
      Object[] items = {null, "before"};
      items[0] = new Object() {
        public String toString() {
          items[1] = "after";
          return "first";
        }
      };
      assertEquals(path ? "first,after" : "&x=first&x=after",
          path ? UrlEncoding.pathVariables("x", items) : UrlEncoding.queryValues("x", items));
    }
  }
}
