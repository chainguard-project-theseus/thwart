package dev.chainguard.thwart.migrate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replacements of source ranges, applied in one pass. An edit that overlaps an earlier one is
 * dropped: the first wins. Edits at the same offset apply in the order they were added.
 */
final class SourceEdits {
  private final List<int[]> ranges = new ArrayList<>();
  private final List<String> replacements = new ArrayList<>();

  /** Replaces {@code [from, to)} with {@code replacement}, unless it overlaps an earlier edit. */
  void add(int from, int to, String replacement) {
    for (int[] e : ranges) {
      if (from < e[1] && e[0] < to || from == to && from == e[0] && e[0] == e[1]) {
        return;
      }
    }
    ranges.add(new int[] {from, to});
    replacements.add(replacement);
  }

  String apply(String source) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < ranges.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingInt(i -> ranges.get(i)[0]));
    StringBuilder out = new StringBuilder(source.length() + 64);
    int pos = 0;
    for (int i : order) {
      out.append(source, pos, ranges.get(i)[0]).append(replacements.get(i));
      pos = ranges.get(i)[1];
    }
    return out.append(source, pos, source.length()).toString();
  }
}
