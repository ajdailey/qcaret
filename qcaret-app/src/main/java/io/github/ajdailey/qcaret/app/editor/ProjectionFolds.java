package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection.DisplayChange;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The fold state of one {@link DisplayProjection}: the collapsed regions ({@link FoldLayer}), the
 * regions the view offers, and the fold commands that change the collapsed set, each installed as
 * one display change through the {@link Host}. Also maps display offsets and rows between the
 * unfolded display, which holds every line, and the folded display a view shows. Confined to the UI
 * thread.
 */
final class ProjectionFolds {
  /** The projection's text, rows and display change events. */
  interface Host {
    /** Returns the text last reported. */
    TextSnapshot text();

    /** Returns the rows of the text last reported. */
    RowIndex rows();

    /** Reports a display change before the collapsed regions change. */
    void changing(DisplayChange change);

    /** Reports a display change after the collapsed regions changed. */
    void changed(DisplayChange change);
  }

  private final Host host;
  private FoldLayer layer = FoldLayer.NONE;
  private int[] regionStarts = new int[0];
  private int[] regionEnds = new int[0];
  private long ops;

  ProjectionFolds(Host host) {
    this.host = host;
  }

  /** Returns the collapsed regions. */
  FoldLayer layer() {
    return layer;
  }

  /** Replaces the collapsed regions inside a display change the projection reports. */
  void setLayer(FoldLayer value) {
    layer = value;
  }

  /** Returns the offered regions' start lines: the array itself, which each change replaces. */
  int[] offered() {
    return regionStarts;
  }

  /** Counts the changes to the collapsed set installed through {@link #apply}. */
  long ops() {
    return ops;
  }

  /** As {@link DisplayProjection#setFoldRegions}. */
  void setFoldRegions(List<int[]> regions) {
    List<int[]> sorted = regions;
    for (int i = 1; i < regions.size(); i++) {
      if (regions.get(i)[0] < regions.get(i - 1)[0]) {
        sorted = new ArrayList<int[]>(regions);
        sorted.sort(
            new Comparator<int[]>() {
              @Override
              public int compare(int[] a, int[] b) {
                return Integer.compare(a[0], b[0]);
              }
            });
        break;
      }
    }
    int last = host.text().lineCount() - 1;
    int[] starts = new int[sorted.size()];
    int[] ends = new int[sorted.size()];
    int count = 0;
    for (int[] r : sorted) {
      if (r[0] < 0 || r[1] <= r[0] || r[1] > last) {
        continue;
      }
      if (count > 0 && starts[count - 1] == r[0]) {
        ends[count - 1] = Math.max(ends[count - 1], r[1]);
      } else {
        starts[count] = r[0];
        ends[count++] = r[1];
      }
    }
    regionStarts = Arrays.copyOf(starts, count);
    regionEnds = Arrays.copyOf(ends, count);
    int n = layer.regionCount();
    int[] keptStarts = new int[n];
    int[] keptEnds = new int[n];
    int kept = 0;
    int lo = Integer.MAX_VALUE;
    int hi = -1;
    for (int i = 0; i < n; i++) {
      int a = layer.regionStart(i);
      int b = layer.regionEnd(i);
      int j = Arrays.binarySearch(regionStarts, a);
      if (j < 0 || regionEnds[j] != b) {
        lo = Math.min(lo, a);
        hi = Math.max(hi, j < 0 ? b : Math.max(b, regionEnds[j]));
      }
      if (j >= 0) {
        keptStarts[kept] = a;
        keptEnds[kept++] = regionEnds[j];
      }
    }
    if (hi >= 0) {
      apply(Arrays.copyOf(keptStarts, kept), Arrays.copyOf(keptEnds, kept), lo, hi);
    }
  }

  int regionCount() {
    return regionStarts.length;
  }

  /** As {@link DisplayProjection#foldEnd}. */
  int foldEnd(int line) {
    int i = layer.regionAt(line);
    if (i >= 0) {
      return layer.regionEnd(i);
    }
    int j = Arrays.binarySearch(regionStarts, line);
    return j >= 0 ? regionEnds[j] : -1;
  }

  /** As {@link DisplayProjection#visibleOffset}. */
  int visibleOffset(int offset, boolean forward) {
    TextSnapshot text = host.text();
    int k = layer.spanHiding(text.lineAt(offset));
    if (k < 0) {
      return offset;
    }
    int after = layer.last(k) + 1;
    if (forward && after < text.lineCount()) {
      return text.offsetOfLine(after);
    }
    return FoldLayer.lineEnd(text, layer.head(k));
  }

  /** As {@link DisplayProjection#fold}. */
  boolean fold(int line) {
    int j = Arrays.binarySearch(regionStarts, line);
    for (j = j >= 0 ? j : -j - 2; j >= 0; j--) {
      if (regionEnds[j] >= line && layer.regionAt(regionStarts[j]) < 0) {
        collapse(regionStarts[j], regionEnds[j]);
        return true;
      }
    }
    return false;
  }

  /** As {@link DisplayProjection#foldAt}. */
  boolean foldAt(int line) {
    int j = Arrays.binarySearch(regionStarts, line);
    if (j < 0 || layer.regionAt(line) >= 0) {
      return false;
    }
    collapse(line, regionEnds[j]);
    return true;
  }

  /** As {@link DisplayProjection#unfold}. */
  boolean unfold(int line) {
    int i = layer.regionAt(line);
    if (i < 0) {
      return false;
    }
    int n = layer.regionCount();
    int[] starts = new int[n - 1];
    int[] ends = new int[n - 1];
    System.arraycopy(layer.starts(), 0, starts, 0, i);
    System.arraycopy(layer.starts(), i + 1, starts, i, n - 1 - i);
    System.arraycopy(layer.ends(), 0, ends, 0, i);
    System.arraycopy(layer.ends(), i + 1, ends, i, n - 1 - i);
    apply(starts, ends, line, layer.regionEnd(i));
    return true;
  }

  /** As {@link DisplayProjection#foldAll}. */
  boolean foldAll() {
    int[][] merged = foldAllRegions();
    if (merged.length == 0) {
      return false;
    }
    apply(merged[0], merged[1], 0, host.text().lineCount() - 1);
    return true;
  }

  /**
   * Returns the collapsed regions plus every offered one as {starts, ends}, or an empty array when
   * no offered one is new.
   */
  int[][] foldAllRegions() {
    int n = regionStarts.length;
    int m = layer.regionCount();
    int[] starts = new int[n + m];
    int[] ends = new int[n + m];
    int count = 0;
    int i = 0;
    int j = 0;
    while (i < n || j < m) {
      if (j >= m || (i < n && regionStarts[i] < layer.regionStart(j))) {
        starts[count] = regionStarts[i];
        ends[count++] = regionEnds[i++];
      } else {
        if (i < n && regionStarts[i] == layer.regionStart(j)) {
          i++;
        }
        starts[count] = layer.regionStart(j);
        ends[count++] = layer.regionEnd(j++);
      }
    }
    if (count == m) {
      return new int[0][];
    }
    return new int[][] {Arrays.copyOf(starts, count), Arrays.copyOf(ends, count)};
  }

  /** As {@link DisplayProjection#unfoldAll}. */
  boolean unfoldAll() {
    if (layer.regionCount() == 0) {
      return false;
    }
    apply(new int[0], new int[0], 0, host.text().lineCount() - 1);
    return true;
  }

  /** As {@link DisplayProjection#reveal}. */
  boolean reveal(int line) {
    if (layer.spanHiding(line) < 0) {
      return false;
    }
    int n = layer.regionCount();
    int[] starts = new int[n];
    int[] ends = new int[n];
    int count = 0;
    int lo = line;
    int hi = line;
    for (int i = 0; i < n; i++) {
      int a = layer.regionStart(i);
      int b = layer.regionEnd(i);
      if (a < line && line <= b) {
        lo = Math.min(lo, a);
        hi = Math.max(hi, b);
      } else {
        starts[count] = a;
        ends[count++] = b;
      }
    }
    apply(Arrays.copyOf(starts, count), Arrays.copyOf(ends, count), lo, hi);
    return true;
  }

  private void collapse(int start, int end) {
    int n = layer.regionCount();
    int at = -Arrays.binarySearch(layer.starts(), start) - 1;
    int[] starts = new int[n + 1];
    int[] ends = new int[n + 1];
    System.arraycopy(layer.starts(), 0, starts, 0, at);
    System.arraycopy(layer.ends(), 0, ends, 0, at);
    starts[at] = start;
    ends[at] = end;
    System.arraycopy(layer.starts(), at, starts, at + 1, n - at);
    System.arraycopy(layer.ends(), at, ends, at + 1, n - at);
    apply(starts, ends, start, end);
  }

  /**
   * Installs collapsed regions that differ from the current ones only within lines {@code [lo,
   * hi]}: one display change from the start of {@code lo} to the end of {@code hi}, each moved out
   * of any hidden span of either state.
   */
  private void apply(int[] starts, int[] ends, int lo, int hi) {
    apply(FoldLayer.of(starts, ends, host.text(), host.rows(), layer), lo, hi);
  }

  /** As {@link #apply(int[], int[], int, int)}, with the regions measured. */
  void apply(FoldLayer next, int lo, int hi) {
    ops++;
    TextSnapshot text = host.text();
    RowIndex index = host.rows();
    int last = text.lineCount() - 1;
    boolean moved = true;
    while (moved) {
      moved = false;
      for (FoldLayer state : new FoldLayer[] {layer, next}) {
        int k = state.spanHiding(lo);
        if (k >= 0) {
          lo = state.head(k);
          moved = true;
        }
        k = hi < last ? state.spanHiding(hi + 1) : -1;
        if (k >= 0) {
          hi = state.last(k);
          moved = true;
        }
      }
    }
    int start = toFolded(layer, text.offsetOfLine(lo) + index.breaksBefore(lo));
    int startRow = rowToFolded(layer, lo + index.breaksBefore(lo));
    int[] old = rangeEnd(layer, hi);
    int[] now = rangeEnd(next, hi);
    DisplayChange change =
        new DisplayChange(
            start, old[0] - start, now[0] - start, old[1] - startRow, now[1] - startRow);
    host.changing(change);
    layer = next;
    host.changed(change);
  }

  /**
   * Returns {display offset, row} where line {@code hi} ends in a fold state: the next line's
   * start, or the text's end and its last row.
   */
  private int[] rangeEnd(FoldLayer state, int hi) {
    TextSnapshot text = host.text();
    RowIndex index = host.rows();
    if (hi == text.lineCount() - 1) {
      return new int[] {
        text.length() + index.totalBreaks() - state.totalChars(),
        text.lineCount() + index.totalBreaks() - state.totalRows() - 1
      };
    }
    return new int[] {
      toFolded(state, text.offsetOfLine(hi + 1) + index.breaksBefore(hi + 1)),
      rowToFolded(state, hi + 1 + index.breaksBefore(hi + 1))
    };
  }

  /**
   * Moves the offered regions through a change: by the line delta after line {@code last}, and
   * through the edits inside {@code [first, last]}; a region left without lines is dropped.
   */
  void shiftRegions(
      TextSnapshot before, TextSnapshot after, List<TextEdit> edits, int first, int last) {
    int n = regionStarts.length;
    if (n == 0) {
      return;
    }
    int delta = after.lineCount() - before.lineCount();
    int lastLine = after.lineCount() - 1;
    FoldLayer.EditMap map = null;
    int[] starts = new int[n];
    int[] ends = new int[n];
    int count = 0;
    for (int i = 0; i < n; i++) {
      int a = regionStarts[i];
      int b = regionEnds[i];
      if (a > last) {
        a += delta;
        b += delta;
      } else if (b >= first) {
        if (map == null) {
          map = new FoldLayer.EditMap(before, edits);
        }
        a = a < first ? a : after.lineAt(map.map(before.offsetOfLine(a)));
        b = b > last ? b + delta : after.lineAt(map.map(before.offsetOfLine(b)));
      }
      if (b <= a || b > lastLine) {
        continue;
      }
      if (count > 0 && starts[count - 1] >= a) {
        ends[count - 1] = Math.max(ends[count - 1], b);
      } else {
        starts[count] = a;
        ends[count++] = b;
      }
    }
    regionStarts = Arrays.copyOf(starts, count);
    regionEnds = Arrays.copyOf(ends, count);
  }

  // Unfolded and folded display coordinates, in the current collapsed regions.

  int toFolded(int offset) {
    return toFolded(layer, offset);
  }

  int toUnfolded(int offset) {
    return toUnfolded(layer, offset);
  }

  int rowToFolded(int row) {
    return rowToFolded(layer, row);
  }

  int rowToUnfolded(int row) {
    return rowToUnfolded(layer, row);
  }

  // Unfolded and folded display coordinates, in any collapsed regions.

  private static int spanStart(FoldLayer layer, int k) {
    return layer.hiddenStart(k);
  }

  private static int spanRow(FoldLayer layer, int k) {
    return layer.hiddenRow(k);
  }

  /** Maps an unfolded display offset; one in hidden text maps to the end of its span's head. */
  private static int toFolded(FoldLayer layer, int offset) {
    int lo = 0;
    int hi = layer.spans() - 1;
    int k = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (spanStart(layer, mid) < offset) {
        k = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (k < 0) {
      return offset;
    }
    int start = spanStart(layer, k);
    if (offset <= start + layer.charsBefore(k + 1) - layer.charsBefore(k)) {
      return start - layer.charsBefore(k);
    }
    return offset - layer.charsBefore(k + 1);
  }

  /** Maps a folded display offset; the end of a span's head maps to the head's end. */
  private static int toUnfolded(FoldLayer layer, int offset) {
    int lo = 0;
    int hi = layer.spans() - 1;
    int k = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (spanStart(layer, mid) - layer.charsBefore(mid) < offset) {
        k = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return k < 0 ? offset : offset + layer.charsBefore(k + 1);
  }

  /** Maps an unfolded row; a hidden row maps to the last row of its span's head. */
  private static int rowToFolded(FoldLayer layer, int row) {
    int lo = 0;
    int hi = layer.spans() - 1;
    int k = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (spanRow(layer, mid) <= row) {
        k = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (k < 0) {
      return row;
    }
    int first = spanRow(layer, k);
    if (row < first + layer.rowsBefore(k + 1) - layer.rowsBefore(k)) {
      return first - layer.rowsBefore(k) - 1;
    }
    return row - layer.rowsBefore(k + 1);
  }

  private static int rowToUnfolded(FoldLayer layer, int row) {
    int lo = 0;
    int hi = layer.spans() - 1;
    int k = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (spanRow(layer, mid) - layer.rowsBefore(mid) <= row) {
        k = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return k < 0 ? row : row + layer.rowsBefore(k + 1);
  }
}
