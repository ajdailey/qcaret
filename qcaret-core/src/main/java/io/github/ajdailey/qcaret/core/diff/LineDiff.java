package io.github.ajdailey.qcaret.core.diff;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A line diff: the shortest edit script between two lists of lines (Myers' greedy algorithm),
 * grouped into hunks with context. The common prefix and suffix are matched first. When the rest
 * needs more than {@value #MAX_EDITS} inserted and removed lines, it is reported as every line
 * removed, then every line added, which is correct but not shortest.
 */
public final class LineDiff {
  /** Edits searched for at most before the rest is reported as one change. */
  public static final int MAX_EDITS = 1000;

  private LineDiff() {}

  /** What happened to a line. */
  public enum Kind {
    /** In both lists. */
    SAME,
    /** Only in the first list. */
    REMOVED,
    /** Only in the second list. */
    ADDED
  }

  /**
   * One line of the script.
   *
   * @param kind what happened to it
   * @param before its index in the first list, or -1 when added
   * @param after its index in the second list, or -1 when removed
   * @param text the line
   */
  public record Line(Kind kind, int before, int after, String text) {}

  /**
   * Changed lines and the unchanged lines around them.
   *
   * @param before the first line's index in the first list
   * @param after the first line's index in the second list
   * @param lines the lines, removed lines before the added lines that replace them
   */
  public record Hunk(int before, int after, List<Line> lines) {}

  /**
   * Computes the edit script. Each run of changes lists its removed lines before its added lines.
   *
   * @param before the first list
   * @param after the second list
   * @param token checked once per edit distance step
   * @return every line of both lists, in order
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static List<Line> diff(List<String> before, List<String> after, CancellationToken token) {
    int n = before.size();
    int m = after.size();
    int prefix = 0;
    while (prefix < n && prefix < m && before.get(prefix).equals(after.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < n - prefix
        && suffix < m - prefix
        && before.get(n - 1 - suffix).equals(after.get(m - 1 - suffix))) {
      suffix++;
    }
    List<Line> out = new ArrayList<Line>(Math.max(n, m) + 8);
    for (int i = 0; i < prefix; i++) {
      out.add(new Line(Kind.SAME, i, i, before.get(i)));
    }
    middle(before, after, prefix, n - suffix, prefix, m - suffix, out, token);
    for (int k = suffix; k > 0; k--) {
      out.add(new Line(Kind.SAME, n - k, m - k, before.get(n - k)));
    }
    return out;
  }

  /**
   * Groups an edit script into hunks: each change with up to {@code context} unchanged lines before
   * and after it; changes closer than {@code 2 * context + 1} lines share a hunk.
   *
   * @param script the output of {@link #diff}
   * @param context unchanged lines kept around each change
   * @return the hunks, empty when nothing changed
   */
  public static List<Hunk> hunks(List<Line> script, int context) {
    List<Hunk> hunks = new ArrayList<Hunk>();
    int size = script.size();
    int done = 0;
    int counted = 0;
    int before = 0;
    int after = 0;
    int i = 0;
    while (i < size) {
      if (script.get(i).kind() == Kind.SAME) {
        i++;
        continue;
      }
      int start = Math.max(done, i - context);
      int end = i;
      while (true) {
        while (end < size && script.get(end).kind() != Kind.SAME) {
          end++;
        }
        int same = end;
        while (same < size && script.get(same).kind() == Kind.SAME) {
          same++;
        }
        if (same < size && same - end <= 2 * context) {
          end = same;
        } else {
          end = Math.min(same, end + context);
          break;
        }
      }
      for (; counted < start; counted++) {
        Kind kind = script.get(counted).kind();
        before += kind == Kind.ADDED ? 0 : 1;
        after += kind == Kind.REMOVED ? 0 : 1;
      }
      hunks.add(new Hunk(before, after, List.copyOf(script.subList(start, end))));
      done = end;
      i = end;
    }
    return hunks;
  }

  /**
   * Diffs {@code before[b0, b1)} against {@code after[a0, a1)}, which share no prefix or suffix.
   */
  private static void middle(
      List<String> before,
      List<String> after,
      int b0,
      int b1,
      int a0,
      int a1,
      List<Line> out,
      CancellationToken token) {
    int n = b1 - b0;
    int m = a1 - a0;
    int max = Math.min(n + m, MAX_EDITS);
    int offset = max + 1;
    int[] v = new int[2 * max + 3];
    List<int[]> trace = new ArrayList<int[]>();
    int found = -1;
    for (int d = 0; d <= max && found < 0; d++) {
      token.check();
      for (int k = -d; k <= d; k += 2) {
        int x;
        if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
          x = v[offset + k + 1];
        } else {
          x = v[offset + k - 1] + 1;
        }
        int y = x - k;
        while (x < n && y < m && before.get(b0 + x).equals(after.get(a0 + y))) {
          x++;
          y++;
        }
        v[offset + k] = x;
        if (x >= n && y >= m) {
          found = d;
          break;
        }
      }
      trace.add(Arrays.copyOfRange(v, offset - d, offset + d + 1));
    }
    if (found < 0) {
      for (int i = b0; i < b1; i++) {
        out.add(new Line(Kind.REMOVED, i, -1, before.get(i)));
      }
      for (int j = a0; j < a1; j++) {
        out.add(new Line(Kind.ADDED, -1, j, after.get(j)));
      }
      return;
    }
    List<Line> reversed = new ArrayList<Line>(n + m);
    int x = n;
    int y = m;
    for (int d = found; d > 0; d--) {
      int[] prev = trace.get(d - 1);
      int k = x - y;
      int prevK;
      if (k == -d || (k != d && at(prev, d - 1, k - 1) < at(prev, d - 1, k + 1))) {
        prevK = k + 1;
      } else {
        prevK = k - 1;
      }
      int prevX = at(prev, d - 1, prevK);
      int prevY = prevX - prevK;
      while (x > prevX && y > prevY) {
        x--;
        y--;
        reversed.add(new Line(Kind.SAME, b0 + x, a0 + y, before.get(b0 + x)));
      }
      if (x == prevX) {
        y--;
        reversed.add(new Line(Kind.ADDED, -1, a0 + y, after.get(a0 + y)));
      } else {
        x--;
        reversed.add(new Line(Kind.REMOVED, b0 + x, -1, before.get(b0 + x)));
      }
    }
    while (x > 0 && y > 0) {
      x--;
      y--;
      reversed.add(new Line(Kind.SAME, b0 + x, a0 + y, before.get(b0 + x)));
    }
    appendOrdered(reversed, out);
  }

  /** Reads {@code V[k]} from the copy taken after step {@code d}. */
  private static int at(int[] copy, int d, int k) {
    return copy[k + d];
  }

  /** Appends a reversed script, each run of changes as its removed lines, then its added lines. */
  private static void appendOrdered(List<Line> reversed, List<Line> out) {
    int i = reversed.size() - 1;
    while (i >= 0) {
      Line line = reversed.get(i);
      if (line.kind() == Kind.SAME) {
        out.add(line);
        i--;
        continue;
      }
      int end = i;
      while (end >= 0 && reversed.get(end).kind() != Kind.SAME) {
        end--;
      }
      for (int j = i; j > end; j--) {
        if (reversed.get(j).kind() == Kind.REMOVED) {
          out.add(reversed.get(j));
        }
      }
      for (int j = i; j > end; j--) {
        if (reversed.get(j).kind() == Kind.ADDED) {
          out.add(reversed.get(j));
        }
      }
      i = end;
    }
  }
}
