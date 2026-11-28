package io.github.ajdailey.qcaret.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes batches applied one after another into one batch with the same effect, in the
 * coordinates before the first. Changes that overlap or touch across batches merge into one. Pairs
 * compose as a balanced tree, so {@code n} batches of one change take {@code O(n log n)}.
 */
final class ChangeComposer {
  private ChangeComposer() {}

  /**
   * Composes batches.
   *
   * @param batches at least one batch, each in the coordinates after the one before it
   * @return one sorted, non-overlapping batch in the coordinates before the first
   */
  static List<Change> compose(List<List<Change>> batches) {
    return compose(batches, 0, batches.size());
  }

  private static List<Change> compose(List<List<Change>> batches, int from, int to) {
    if (to - from == 1) {
      return batches.get(from);
    }
    int mid = (from + to) >>> 1;
    return compose(compose(batches, from, mid), compose(batches, mid, to));
  }

  /**
   * Composes two batches.
   *
   * @param first the batch from text 0 to text 1
   * @param second the batch from text 1 to text 2
   * @return the batch from text 0 to text 2
   */
  static List<Change> compose(List<Change> first, List<Change> second) {
    int na = first.size();
    int nb = second.size();
    int[] firstStart = new int[na];
    int shift = 0;
    for (int i = 0; i < na; i++) {
      Change c = first.get(i);
      firstStart[i] = c.offset() + shift;
      shift += c.inserted().length() - c.removed().length();
    }
    List<Change> out = new ArrayList<Change>(Math.max(na, nb));
    int shiftBefore = 0;
    int i = 0;
    int j = 0;
    while (i < na || j < nb) {
      // A cluster: text-1 intervals of first's inserted text and second's removed text that
      // overlap or touch. Every position in it lies in one of them.
      int start =
          j >= nb || i < na && firstStart[i] <= second.get(j).offset()
              ? firstStart[i]
              : second.get(j).offset();
      int end = start;
      int i0 = i;
      int j0 = j;
      while (true) {
        if (i < na && firstStart[i] <= end) {
          end = Math.max(end, firstStart[i] + first.get(i).inserted().length());
          i++;
        } else if (j < nb && second.get(j).offset() <= end) {
          Change c = second.get(j);
          end = Math.max(end, c.offset() + c.removed().length());
          j++;
        } else {
          break;
        }
      }
      if (i - i0 + j - j0 == 1) {
        // One change alone: only its offset moves into text 0.
        if (i > i0) {
          Change c = first.get(i0);
          out.add(c);
          shiftBefore += c.inserted().length() - c.removed().length();
        } else {
          Change c = second.get(j0);
          out.add(new Change(c.offset() - shiftBefore, c.removed(), c.inserted()));
        }
        continue;
      }
      char[] middle = new char[end - start];
      for (int k = i0; k < i; k++) {
        String inserted = first.get(k).inserted();
        inserted.getChars(0, inserted.length(), middle, firstStart[k] - start);
      }
      for (int k = j0; k < j; k++) {
        Change c = second.get(k);
        c.removed().getChars(0, c.removed().length(), middle, c.offset() - start);
      }
      StringBuilder removed = new StringBuilder();
      int at = start;
      for (int k = i0; k < i; k++) {
        Change c = first.get(k);
        removed.append(middle, at - start, firstStart[k] - at).append(c.removed());
        at = firstStart[k] + c.inserted().length();
      }
      removed.append(middle, at - start, end - at);
      StringBuilder inserted = new StringBuilder();
      at = start;
      for (int k = j0; k < j; k++) {
        Change c = second.get(k);
        inserted.append(middle, at - start, c.offset() - at).append(c.inserted());
        at = c.offset() + c.removed().length();
      }
      inserted.append(middle, at - start, end - at);
      out.add(new Change(start - shiftBefore, removed.toString(), inserted.toString()));
      for (int k = i0; k < i; k++) {
        Change c = first.get(k);
        shiftBefore += c.inserted().length() - c.removed().length();
      }
    }
    return out;
  }
}
