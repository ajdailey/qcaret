package io.github.ajdailey.qcaret.ui.swt.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;

/**
 * The find matches an editor highlights, as model offset pairs, and their backgrounds within one
 * row. A secondary selection painted on the same chars takes precedence over a match.
 */
final class MatchLayer {
  private int[] ranges = new int[0];

  /**
   * Replaces the matches.
   *
   * @param value model offset pairs, ascending
   */
  void set(int[] value) {
    ranges = value.clone();
  }

  /**
   * Returns the matches.
   *
   * @return model offset pairs, ascending
   */
  int[] ranges() {
    return ranges.clone();
  }

  /**
   * Returns a row's styles: the secondary selections and the match backgrounds outside them, sorted
   * by offset.
   *
   * @param selected the secondary selection styles, sorted, in display offsets
   * @param rowOffset the row's display offset
   * @param modelStart the model offset of the row's first char
   * @param rowLength the row's chars
   * @param background the match background
   * @return the styles
   */
  StyleRange[] merge(
      StyleRange[] selected, int rowOffset, int modelStart, int rowLength, Color background) {
    int modelEnd = modelStart + rowLength;
    int first = firstEndingAfter(modelStart);
    if (first * 2 >= ranges.length || ranges[first * 2] >= modelEnd) {
      return selected;
    }
    List<StyleRange> out = new ArrayList<StyleRange>();
    for (StyleRange s : selected) {
      out.add(s);
    }
    for (int i = first * 2; i < ranges.length && ranges[i] < modelEnd; i += 2) {
      int start = rowOffset + Math.max(ranges[i], modelStart) - modelStart;
      int end = rowOffset + Math.min(ranges[i + 1], modelEnd) - modelStart;
      for (StyleRange s : selected) {
        if (s.start + s.length <= start || s.start >= end) {
          continue;
        }
        if (s.start > start) {
          out.add(new StyleRange(start, s.start - start, null, background));
        }
        start = Math.max(start, s.start + s.length);
      }
      if (end > start) {
        out.add(new StyleRange(start, end - start, null, background));
      }
    }
    out.sort(
        new Comparator<StyleRange>() {
          @Override
          public int compare(StyleRange a, StyleRange b) {
            return Integer.compare(a.start, b.start);
          }
        });
    return out.toArray(new StyleRange[0]);
  }

  /** Returns the index of the first pair whose end is after {@code offset}. */
  private int firstEndingAfter(int offset) {
    int lo = 0;
    int hi = ranges.length / 2;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (ranges[mid * 2 + 1] <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
