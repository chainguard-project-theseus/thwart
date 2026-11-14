package dev.chainguard.thwart.migrate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A unified diff of two line lists (Myers' algorithm, three lines of context). */
final class LineDiff {
  private LineDiff() {}

  private static final int CONTEXT = 3;
  /** Past this many edits the diff is printed as one hunk replacing everything. */
  private static final int MAX_EDITS = 4000;

  private static final class Op {
    private final char kind;
    private final String line;
    private final int a;
    private final int b;

    Op(char kind, String line, int a, int b) {
      this.kind = kind;
      this.line = line;
      this.a = a;
      this.b = b;
    }

    char kind() {
      return kind;
    }

    String line() {
      return line;
    }

    int a() {
      return a;
    }

    int b() {
      return b;
    }
  }

  /** The hunks ({@code @@ ... @@} and the {@code ' '}/{@code -}/{@code +} lines), no headers. */
  static List<String> unified(List<String> a, List<String> b) {
    List<Op> ops = edits(a, b);
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < ops.size()) {
      if (ops.get(i).kind() == ' ') {
        i++;
        continue;
      }
      // A hunk: from CONTEXT lines before this change to CONTEXT lines after the last change
      // that is within 2 * CONTEXT unchanged lines of the previous one.
      int start = Math.max(0, i - CONTEXT);
      int end = i;
      int same = 0;
      for (int j = i; j < ops.size(); j++) {
        if (ops.get(j).kind() == ' ') {
          if (++same > 2 * CONTEXT) {
            break;
          }
        } else {
          same = 0;
          end = j;
        }
      }
      int stop = Math.min(ops.size(), end + 1 + CONTEXT);
      int aStart = ops.get(start).a();
      int bStart = ops.get(start).b();
      int aCount = 0;
      int bCount = 0;
      List<String> body = new ArrayList<>();
      for (int j = start; j < stop; j++) {
        Op op = ops.get(j);
        body.add(op.kind() + op.line());
        if (op.kind() != '+') {
          aCount++;
        }
        if (op.kind() != '-') {
          bCount++;
        }
      }
      out.add("@@ -" + range(aStart, aCount) + " +" + range(bStart, bCount) + " @@");
      out.addAll(body);
      i = stop;
    }
    return out;
  }

  private static String range(int start, int count) {
    // Unified diff convention: an empty range names the line before it.
    return (count == 0 ? start : start + 1) + "," + count;
  }

  /** The edit script; each op carries the 0-based positions in a and b where it applies. */
  private static List<Op> edits(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    int max = n + m;
    int[] v = new int[2 * max + 2];
    List<int[]> trace = new ArrayList<>();
    boolean done = n == 0 && m == 0;
    for (int d = 0; d <= max && !done; d++) {
      if (d > MAX_EDITS) {
        return replaceAll(a, b);
      }
      trace.add(Arrays.copyOfRange(v, max - d, max + d + 2));
      for (int k = -d; k <= d; k += 2) {
        int x = (k == -d || (k != d && v[max + k - 1] < v[max + k + 1]))
            ? v[max + k + 1] : v[max + k - 1] + 1;
        int y = x - k;
        while (x < n && y < m && a.get(x).equals(b.get(y))) {
          x++;
          y++;
        }
        v[max + k] = x;
        if (x >= n && y >= m) {
          done = true;
          break;
        }
      }
    }
    // Walk back from (n, m) through the saved V arrays (trace.get(d) is V before step d).
    List<Op> ops = new ArrayList<>();
    int x = n;
    int y = m;
    for (int d = trace.size() - 1; d >= 0; d--) {
      int[] prev = trace.get(d);
      int k = x - y;
      int prevK;
      int prevX;
      if (d == 0) {
        prevK = 0;
        prevX = 0;
      } else {
        // prev holds V[-d .. d+1] at offsets 0 .. 2d+1.
        boolean down = k == -d || (k != d && prev[k - 1 + d] < prev[k + 1 + d]);
        prevK = down ? k + 1 : k - 1;
        prevX = prev[prevK + d];
      }
      int prevY = prevX - prevK;
      while (x > prevX && y > prevY) {
        x--;
        y--;
        ops.add(new Op(' ', a.get(x), x, y));
      }
      if (d > 0) {
        if (x == prevX) {
          y--;
          ops.add(new Op('+', b.get(y), x, y));
        } else {
          x--;
          ops.add(new Op('-', a.get(x), x, y));
        }
      }
    }
    Collections.reverse(ops);
    return ops;
  }

  private static List<Op> replaceAll(List<String> a, List<String> b) {
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < a.size(); i++) {
      ops.add(new Op('-', a.get(i), i, 0));
    }
    for (int j = 0; j < b.size(); j++) {
      ops.add(new Op('+', b.get(j), a.size(), j));
    }
    return ops;
  }
}
