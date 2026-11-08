package io.github.ajdailey.qcaret.core.syntax;

import java.util.ArrayList;
import java.util.List;

/** Merges fold region lists. */
public final class FoldRegions {
  private FoldRegions() {}

  /**
   * Merges two region lists into one region per start line, the largest, spanning at least two
   * lines.
   *
   * @param a {@code {startLine, endLine}} pairs, ascending by start line, one per start line
   * @param b another such list
   * @return the merged list, ascending; {@code a} or {@code b} itself when the other is empty
   */
  public static List<int[]> merge(List<int[]> a, List<int[]> b) {
    if (b.isEmpty()) {
      return a;
    }
    if (a.isEmpty()) {
      return b;
    }
    List<int[]> out = new ArrayList<int[]>(a.size() + b.size());
    int i = 0;
    int j = 0;
    while (i < a.size() || j < b.size()) {
      int[] next;
      if (j >= b.size() || (i < a.size() && a.get(i)[0] < b.get(j)[0])) {
        next = a.get(i++);
      } else if (i >= a.size() || b.get(j)[0] < a.get(i)[0]) {
        next = b.get(j++);
      } else {
        int[] x = a.get(i++);
        int[] y = b.get(j++);
        next = x[1] >= y[1] ? x : y;
      }
      if (next[1] > next[0]) {
        out.add(next);
      }
    }
    return out;
  }
}
