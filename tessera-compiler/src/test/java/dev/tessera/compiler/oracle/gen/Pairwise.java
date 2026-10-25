package dev.tessera.compiler.oracle.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * A deterministic pairwise covering array (AETG-style greedy, #321): rows of axis value indices
 * such that every pair of values of two different axes appears in at least one row.
 *
 * <p>One axis may be <em>conditional</em>: its value only counts when a controlling axis has an
 * enabling value (the insertion point only matters for {@code t:insert}/{@code t:replace}). Pairs
 * of a conditional value with a non-enabling controlling value are not required, and rows where it
 * doesn't count carry its value 0.
 */
final class Pairwise {

  private static final int CANDIDATES = 24;

  private final int[] sizes;
  private final int conditional;
  private final int controlling;
  private final IntPredicate enables;
  private final boolean[][][] covered;
  private final Random random;

  /**
   * @param sizes the number of values of each axis
   * @param conditional the conditional axis, or -1
   * @param controlling the axis that enables it
   * @param enables which values of {@code controlling} enable {@code conditional}
   * @param seed the seed of the tie-breaking random source
   */
  Pairwise(int[] sizes, int conditional, int controlling, IntPredicate enables, long seed) {
    this.sizes = sizes.clone();
    this.conditional = conditional;
    this.controlling = controlling;
    this.enables = enables;
    this.random = new Random(seed);
    covered = new boolean[sizes.length][sizes.length][];
    for (int a = 0; a < sizes.length; a++) {
      for (int b = a + 1; b < sizes.length; b++) {
        covered[a][b] = new boolean[sizes[a] * sizes[b]];
      }
    }
  }

  /** The covering array: {@code forced} rows first, then greedy rows until every pair is covered. */
  List<int[]> cover(List<int[]> forced) {
    List<int[]> rows = new ArrayList<>();
    for (int[] row : forced) {
      mark(row);
      rows.add(row.clone());
    }
    int[] target;
    while ((target = firstUncovered()) != null) {
      int[] best = null;
      int bestScore = -1;
      for (int i = 0; i < CANDIDATES; i++) {
        int[] candidate = candidate(target);
        int score = newPairs(candidate);
        if (score > bestScore) {
          best = candidate;
          bestScore = score;
        }
      }
      mark(best);
      rows.add(best);
    }
    return rows;
  }

  /** Whether the pair of axes {@code a < b} counts in {@code row}. */
  private boolean counts(int[] row, int a, int b) {
    if (a == conditional || b == conditional) {
      return enables.test(row[controlling]);
    }
    return true;
  }

  private boolean required(int a, int va, int b, int vb) {
    if (a == conditional && b == controlling) {
      return enables.test(vb);
    }
    if (b == conditional && a == controlling) {
      return enables.test(va);
    }
    return true;
  }

  private int[] firstUncovered() {
    for (int a = 0; a < sizes.length; a++) {
      for (int b = a + 1; b < sizes.length; b++) {
        boolean[] c = covered[a][b];
        for (int i = 0; i < c.length; i++) {
          int va = i / sizes[b];
          int vb = i % sizes[b];
          if (!c[i] && required(a, va, b, vb)) {
            return new int[] {a, va, b, vb};
          }
        }
      }
    }
    return null;
  }

  private int[] candidate(int[] target) {
    int n = sizes.length;
    int[] row = new int[n];
    boolean[] fixed = new boolean[n];
    row[target[0]] = target[1];
    row[target[2]] = target[3];
    fixed[target[0]] = true;
    fixed[target[2]] = true;
    List<Integer> order = new ArrayList<>();
    for (int x = 0; x < n; x++) {
      if (!fixed[x]) {
        order.add(x);
      }
    }
    java.util.Collections.shuffle(order, random);
    // The controlling axis is chosen before the conditional one.
    if (conditional >= 0 && order.contains(conditional) && order.contains(controlling)) {
      order.remove(Integer.valueOf(controlling));
      order.add(order.indexOf(conditional), controlling);
    }
    for (int x : order) {
      int bestValue = 0;
      int bestScore = -1;
      int ties = 0;
      for (int v = 0; v < sizes[x]; v++) {
        if (x == controlling && fixed[conditional] && !enables.test(v)) {
          continue; // the target pair needs the conditional axis to count
        }
        if (x == conditional && !enables.test(row[controlling])) {
          if (v != 0) {
            continue;
          }
        }
        row[x] = v;
        int score = 0;
        for (int y = 0; y < n; y++) {
          if (fixed[y] && y != x) {
            int a = Math.min(x, y);
            int b = Math.max(x, y);
            if (counts(row, a, b) && !covered[a][b][row[a] * sizes[b] + row[b]]) {
              score++;
            }
          }
        }
        if (score > bestScore) {
          bestScore = score;
          bestValue = v;
          ties = 1;
        } else if (score == bestScore && random.nextInt(++ties) == 0) {
          bestValue = v;
        }
      }
      row[x] = bestValue;
      fixed[x] = true;
    }
    return row;
  }

  private int newPairs(int[] row) {
    int count = 0;
    for (int a = 0; a < sizes.length; a++) {
      for (int b = a + 1; b < sizes.length; b++) {
        if (counts(row, a, b) && !covered[a][b][row[a] * sizes[b] + row[b]]) {
          count++;
        }
      }
    }
    return count;
  }

  private void mark(int[] row) {
    for (int a = 0; a < sizes.length; a++) {
      for (int b = a + 1; b < sizes.length; b++) {
        if (counts(row, a, b)) {
          covered[a][b][row[a] * sizes[b] + row[b]] = true;
        }
      }
    }
  }
}
