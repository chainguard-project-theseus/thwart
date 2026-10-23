package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultLinksTest {
  private final DefaultLinks root = new DefaultLinks("");
  private final DefaultLinks app = new DefaultLinks("/app");

  private static String[] n(String... names) {
    return names;
  }

  private static Object[] v(Object... values) {
    return values;
  }

  @Test
  void contextPathPrependedToContextRelativePaths() {
    assertEquals("/app/owners", app.build("/owners", n(), v()));
    assertEquals("/owners", root.build("/owners", n(), v()));
  }

  @Test
  void literalLinksPreserveQueriesFragmentsAndUnmatchedPlaceholders() {
    assertEquals("/app/owners?sort=name#top", app.build("/owners?sort=name#top", n(), v()));
    assertEquals("/app/{id}#top?x=1", app.build("/{id}#top?x=1", n(), v()));
    assertEquals("relative?x=1#top", app.build("relative?x=1#top", n(), v()));
    assertEquals("", app.build("", n(), v()));
  }

  @Test
  void absoluteUrlsUntouched() {
    assertEquals("https://example.com/x", app.build("https://example.com/x", n(), v()));
    assertEquals("//cdn.example.com/a.js", app.build("//cdn.example.com/a.js", n(), v()));
    assertEquals("mailto:a@b.c", app.build("mailto:a@b.c", n(), v()));
  }

  @Test
  void pageRelativeUntouched() {
    assertEquals("edit?x=1", app.build("edit", n("x"), v(1)));
  }

  @Test
  void pathVariablesSubstitutedAndRemovedFromQuery() {
    assertEquals(
        "/app/owners/42/pets/7/edit?q=term",
        app.build("/owners/{ownerId}/pets/{petId}/edit", n("ownerId", "q", "petId"), v(42, "term", 7)));
  }

  @Test
  void pathVariableUsedTwice() {
    assertEquals("/a/1/b/1", root.build("/a/{id}/b/{id}", n("id"), v(1)));
  }

  @Test
  void unknownPlaceholderLeftAsWritten() {
    assertEquals("/a/{x}?y=1", root.build("/a/{x}", n("y"), v(1)));
  }

  @Test
  void pathVariableEncoding() {
    assertEquals("/f/a%2Fb", root.build("/f/{x}", n("x"), v("a/b")));
    assertEquals("/f/a%3Fb%3Dc", root.build("/f/{x}", n("x"), v("a?b=c")));
    assertEquals("/f/a%23b", root.build("/f/{x}", n("x"), v("a#b")));
    assertEquals("/f/a%20b", root.build("/f/{x}", n("x"), v("a b")));
    assertEquals("/f/caf%C3%A9%E2%82%AC", root.build("/f/{x}", n("x"), v("café€")));
    assertEquals("/f/..%2F..%2Fetc", root.build("/f/{x}", n("x"), v("../../etc")));
    assertEquals("/f/%5C%5Cevil.com", root.build("/f/{x}", n("x"), v("\\\\evil.com")));
    assertEquals("/f/javascript%3Aalert%281%29",
        root.build("/f/{x}", n("x"), v("javascript:alert(1)")));
  }

  @Test
  void encodedValueIsNotReexpanded() {
    assertEquals("/f/%7By%7D/2", root.build("/f/{x}/{y}", n("x", "y"), v("{y}", 2)));
  }

  @Test
  void dotSegmentPathVariablesRejected() {
    for (Object bad : new Object[] {"..", "."}) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> root.build("/f/{x}/edit", n("x"), v(bad)));
      assertTrue(e.getMessage().contains("'x'"), e.getMessage());
    }
  }

  @Test
  void emptyOrNullPathVariableRejected() {
    // "/{x}/evil.com" with x="" would otherwise become the protocol-relative "//evil.com".
    assertThrows(IllegalArgumentException.class, () -> root.build("/{x}/evil.com", n("x"), v("")));
    assertThrows(IllegalArgumentException.class, () -> root.build("/{x}/evil.com", n("x"), v((Object) null)));
  }

  @Test
  void queryParamsInDeclarationOrderWithNullsAsEmptyValues() {
    // Thymeleaf turns a null value into "": @{/s(a=${null})} is /s?a=, not /s?a.
    assertEquals("/s?z=1&a=&m=x", root.build("/s", n("z", "a", "m"), v(1, null, "x")));
    assertEquals("/s?a=", root.build("/s", n("a"), v("")));
  }

  @Test
  void valuelessParamsAsBareNames() {
    assertEquals(
        "/app/cart?add&itemId=7", app.build("/cart", n("add", "itemId"), v(Links.NO_VALUE, 7)));
    assertEquals("/s?a=1&form#top", root.build("/s?a=1#top", n("form"), v(Links.NO_VALUE)));
    assertEquals("/s?k%26%3D", root.build("/s", n("k&="), v(Links.NO_VALUE)));
  }

  @Test
  void collectionValuesRepeatTheName() {
    assertEquals(
        "/s?t=a&t&t=b%26c%3Dd&x=1",
        root.build("/s", n("t", "x"), v(Arrays.asList("a", null, "b&c=d"), 1)));
    assertEquals("/s?t=1&t=2", root.build("/s", n("t"), v((Object) new int[] {1, 2})));
    assertEquals("/s?t=a&t", root.build("/s", n("t"), v((Object) new String[] {"a", null})));
    assertEquals("/s?x=1", root.build("/s", n("t", "x"), v(Collections.emptyList(), 1)));
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
      assertEquals("/s?k%26%3D=" + first + "&k%26%3D=" + second,
          root.build("/s", n("k&="), v(arrays[i])));
      assertEquals("/s/" + first + "," + second,
          root.build("/s/{x}", n("x"), v(arrays[i])));
    }
  }

  @Test
  void emptyArraysEmitNoQueryAndCannotBecomeEmptyPathSegments() {
    Object[] arrays = {
      new Object[0], new int[0], new long[0], new short[0], new byte[0],
      new char[0], new double[0], new float[0], new boolean[0]
    };
    for (Object array : arrays) {
      assertEquals("/s?keep=1#top", root.build("/s#top", n("x", "keep"), v(array, 1)));
      assertThrows(IllegalArgumentException.class,
          () -> root.build("/{x}/evil.com", n("x"), v(array)));
    }
  }

  @Test
  void nullEmptyAndValuelessElementsRetainTheirDifferentMeanings() {
    List<Object> items = Arrays.asList(null, "", Links.NO_VALUE, "a&b");
    assertEquals("/s?k%26&k%26=&k%26&k%26=a%26b",
        root.build("/s", n("k&"), v(items)));
    assertEquals("/s/,,Links.NO_VALUE,a%26b",
        root.build("/s/{x}", n("x"), v(items)));
    assertEquals("/s/,", root.build("/s/{x}", n("x"), v(Arrays.asList(null, null))));
  }

  @Test
  void multiValuePathEncodingCannotIntroduceUrlStructureOrDotSegments() {
    for (Object bad : new Object[] {new char[] {'.'}, new String[] {".."},
        Collections.singletonList(""), Collections.singletonList(null)}) {
      assertThrows(IllegalArgumentException.class,
          () -> root.build("/{x}/evil.com", n("x"), v(bad)));
    }
    assertEquals("/s/%252e%252e,%2F%2Fevil.com,%3Fq%3Dx%23f,%5C%5Cevil.com,%2C,%00",
        root.build("/s/{x}", n("x"), v(Arrays.asList("%2e%2e", "//evil.com", "?q=x#f",
            "\\\\evil.com", ",", "\u0000"))));
  }

  @Test
  void singleUseIterablesConvertEachElementBeforeAdvancing() {
    for (String path : new String[] {"/s", "/s/{x}"}) {
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
      assertEquals(path.equals("/s") ? "/s?x=a/0&x=a/1" : "/s/a%2F0,a%2F1",
          root.build(path, n("x"), v(values)));
      assertEquals("next0;text0;next1;text1;", events.toString());
    }
  }

  @Test
  void objectArraysAreReadAsEachElementIsRendered() {
    for (String path : new String[] {"/s", "/s/{x}"}) {
      Object[] items = {null, "before"};
      items[0] = new Object() {
        public String toString() {
          items[1] = "after";
          return "first";
        }
      };
      assertEquals(path.equals("/s") ? "/s?x=first&x=after" : "/s/first,after",
          root.build(path, n("x"), v((Object) items)));
    }
  }

  @Test
  void collectionPathVariableJoinedWithCommas() {
    assertEquals("/f/a,,b%2Fc", root.build("/f/{x}", n("x"), v(Arrays.asList("a", null, "b/c"))));
    assertEquals("/f/a%2C,b", root.build("/f/{x}", n("x"), v(Arrays.asList("a,", "b"))));
    // Each element is encoded on its own: no element can add a '/', '?' or '#', and a dot segment
    // among others is part of one segment ("..,a"), not a segment of its own.
    assertEquals("/f/..,a/y", root.build("/f/{x}/y", n("x"), v(Arrays.asList("..", "a"))));
    assertEquals(
        "/f/%252e%252e,%3F%23", root.build("/f/{x}", n("x"), v(Arrays.asList("%2e%2e", "?#"))));
  }

  @Test
  void badCollectionOrValuelessPathVariablesRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> root.build("/{x}/evil.com", n("x"), v(Collections.emptyList())));
    assertThrows(
        IllegalArgumentException.class,
        () -> root.build("/f/{x}", n("x"), v(Arrays.asList(".."))));
    assertThrows(
        IllegalArgumentException.class,
        () -> root.build("/f/{x}", n("x"), v((Object) new String[] {"."})));
    assertThrows(
        IllegalArgumentException.class,
        () -> root.build("/f/{x}", n("x"), v(Arrays.asList(""))));
    assertThrows(
        IllegalArgumentException.class,
        () -> root.build("/{x}/evil.com", n("x"), v((Object) new String[] {null})));
    assertThrows(
        IllegalArgumentException.class, () -> root.build("/{x}/y", n("x"), v(Links.NO_VALUE)));
  }

  @Test
  void queryParamEncoding() {
    assertEquals(
        "/s?q=a%26b%3Dc%2Bd%23e%3Ff%20g&k%26=%C3%A9",
        root.build("/s", n("q", "k&"), v("a&b=c+d#e?f g", "é")));
  }

  @Test
  void queryAppendedToExistingQueryAndBeforeFragment() {
    assertEquals("/app/s?a=1&b=2#top", app.build("/s?a=1#top", n("b"), v(2)));
    assertEquals("/s?b=2#top", root.build("/s#top", n("b"), v(2)));
  }

  @Test
  void mismatchedArraysRejected() {
    assertThrows(IllegalArgumentException.class, () -> root.build("/s", n("a"), v()));
  }
}
