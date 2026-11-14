package dev.chainguard.thwart.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineDiffTest {

  @Test
  void equalListsHaveNoHunks() {
    assertEquals(List.of(), LineDiff.unified(List.of("a", "b"), List.of("a", "b")));
    assertEquals(List.of(), LineDiff.unified(List.of(), List.of()));
  }

  @Test
  void oneChangedLineWithContext() {
    List<String> a = List.of("1", "2", "3", "4", "5", "6", "7", "8");
    List<String> b = List.of("1", "2", "3", "4", "five", "6", "7", "8");
    assertEquals(List.of("@@ -2,7 +2,7 @@", " 2", " 3", " 4", "-5", "+five", " 6", " 7", " 8"),
        LineDiff.unified(a, b));
  }

  @Test
  void insertionAndDeletionAtTheEnds() {
    assertEquals(List.of("@@ -0,0 +1,1 @@", "+new"), LineDiff.unified(List.of(), List.of("new")));
    assertEquals(List.of("@@ -1,2 +1,1 @@", " a", "-b"), LineDiff.unified(List.of("a", "b"), List.of("a")));
  }

  @Test
  void distantChangesGetSeparateHunks() {
    List<String> a = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      a.add(String.valueOf(i));
    }
    List<String> b = new ArrayList<>(a);
    b.set(1, "two");
    b.set(17, "eighteen");
    List<String> diff = LineDiff.unified(a, b);
    assertEquals(List.of("@@ -1,5 +1,5 @@", " 1", "-2", "+two", " 3", " 4", " 5",
        "@@ -15,6 +15,6 @@", " 15", " 16", " 17", "-18", "+eighteen", " 19", " 20"), diff);
  }
}
