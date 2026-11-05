package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.util.Arrays;
import java.util.List;

/**
 * A view's collapsed fold regions, sorted by start line, at most one per start line, and the hidden
 * spans they make. Region {@code (a, b)} hides lines {@code a + 1} to {@code b}: in display text
 * line {@code a} is followed directly by line {@code b}'s delimiter. Overlapping and nested regions
 * merge into one span, keyed by its visible head line and its last hidden line, with the display
 * chars and rows it hides (the model chars from the head's end to the last line's end and the
 * virtual breaks of the hidden lines) and where its hidden text starts in the unfolded display
 * text, so a lookup reads no text. Immutable once placed.
 */
final class FoldLayer {
  static final FoldLayer NONE = new FoldLayer(new int[0], new int[0], new int[0], new int[0]);

  private final int[] starts;
  private final int[] ends;
  private final int[] heads;
  private final int[] lasts;
  private final int[] chars;
  private final int[] rows;
  private final int[] hiddenStarts;
  private final int[] hiddenRows;

  private FoldLayer(int[] starts, int[] ends, int[] heads, int[] lasts) {
    this.starts = starts;
    this.ends = ends;
    this.heads = heads;
    this.lasts = lasts;
    this.chars = new int[heads.length + 1];
    this.rows = new int[heads.length + 1];
    this.hiddenStarts = new int[heads.length];
    this.hiddenRows = new int[heads.length];
  }

  /**
   * Builds a layer of regions; a span the {@code reuse} layer has with the same lines keeps its
   * sizes and positions, the others are measured in {@code text} and {@code index}.
   *
   * @param starts region start lines, ascending, distinct
   * @param ends region end lines, each above its start
   */
  static FoldLayer of(int[] starts, int[] ends, TextContent text, RowIndex index, FoldLayer reuse) {
    int n = starts.length;
    int[] heads = new int[n];
    int[] lasts = new int[n];
    int s = 0;
    for (int i = 0; i < n; i++) {
      if (s > 0 && starts[i] <= lasts[s - 1]) {
        lasts[s - 1] = Math.max(lasts[s - 1], ends[i]);
      } else {
        heads[s] = starts[i];
        lasts[s] = ends[i];
        s++;
      }
    }
    FoldLayer layer = new FoldLayer(starts, ends, Arrays.copyOf(heads, s), Arrays.copyOf(lasts, s));
    layer.measure(text, index, reuse);
    return layer;
  }

  /** Fills sizes and positions, reusing a span of {@code reuse} with the same lines. */
  private void measure(TextContent text, RowIndex index, FoldLayer reuse) {
    int j = 0;
    for (int k = 0; k < heads.length; k++) {
      int h = heads[k];
      int e = lasts[k];
      while (reuse != null && j < reuse.heads.length && reuse.heads[j] < h) {
        j++;
      }
      int c;
      int r;
      if (reuse != null && j < reuse.heads.length && reuse.heads[j] == h && reuse.lasts[j] == e) {
        c = reuse.chars[j + 1] - reuse.chars[j];
        r = reuse.rows[j + 1] - reuse.rows[j];
        hiddenStarts[k] = reuse.hiddenStarts[j];
        hiddenRows[k] = reuse.hiddenRows[j];
      } else {
        int before = index.breaksBefore(h + 1);
        int breaks = index.breaksBefore(e + 1) - before;
        int headEnd = lineEnd(text, h);
        c = lineEnd(text, e) - headEnd + breaks;
        r = e - h + breaks;
        hiddenStarts[k] = headEnd + before;
        hiddenRows[k] = h + 1 + before;
      }
      chars[k + 1] = chars[k] + c;
      rows[k + 1] = rows[k] + r;
    }
  }

  /** Returns the layer measured again, after the rows changed. */
  FoldLayer remeasured(TextContent text, RowIndex index) {
    if (heads.length == 0) {
      return this;
    }
    FoldLayer layer = new FoldLayer(starts, ends, heads, lasts);
    layer.measure(text, index, null);
    return layer;
  }

  /**
   * Sets the positions of spans {@code [from, to)} from the text and rows after a change; called
   * once, before the layer is read.
   */
  void place(TextContent text, RowIndex index, int from, int to) {
    for (int k = from; k < to; k++) {
      int h = heads[k];
      int before = index.breaksBefore(h + 1);
      hiddenStarts[k] = lineEnd(text, h) + before;
      hiddenRows[k] = h + 1 + before;
    }
  }

  /** Returns the unfolded display offset where span {@code k}'s hidden text starts. */
  int hiddenStart(int k) {
    return hiddenStarts[k];
  }

  /** Returns the unfolded display row of span {@code k}'s first hidden line. */
  int hiddenRow(int k) {
    return hiddenRows[k];
  }

  /** Returns the offset where a line's text ends, before its delimiter; one lookup in the text. */
  static int lineEnd(TextContent text, int line) {
    LineView view = text.lineView(line, new LineView());
    return view.start() + view.length();
  }

  int regionCount() {
    return starts.length;
  }

  int regionStart(int i) {
    return starts[i];
  }

  int regionEnd(int i) {
    return ends[i];
  }

  int[] starts() {
    return starts;
  }

  int[] ends() {
    return ends;
  }

  /** Returns the index of the region starting at a line, or -1. */
  int regionAt(int line) {
    int i = Arrays.binarySearch(starts, line);
    return i >= 0 ? i : -1;
  }

  int spans() {
    return heads.length;
  }

  int head(int k) {
    return heads[k];
  }

  int last(int k) {
    return lasts[k];
  }

  /** Returns the display chars the spans before span {@code k} hide. */
  int charsBefore(int k) {
    return chars[k];
  }

  /** Returns the display rows the spans before span {@code k} hide. */
  int rowsBefore(int k) {
    return rows[k];
  }

  int totalChars() {
    return chars[heads.length];
  }

  int totalRows() {
    return rows[heads.length];
  }

  /** Returns the index of the first span whose head is at or after a line. */
  int firstSpanFrom(int line) {
    int lo = 0;
    int hi = heads.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (heads[mid] < line) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** Returns the span whose head is a line, or -1. */
  int spanWithHead(int line) {
    int k = firstSpanFrom(line);
    return k < heads.length && heads[k] == line ? k : -1;
  }

  /** Returns the span that hides a line, or -1. */
  int spanHiding(int line) {
    int k = firstSpanFrom(line) - 1;
    return k >= 0 && line <= lasts[k] ? k : -1;
  }

  /** The result of {@link #afterEdit}: the new layer and the line range its change covers. */
  record Edited(FoldLayer layer, int from, int to) {}

  /**
   * Maps the layer through a document change. A region expands when an edit touches its hidden
   * text, from its head's end to its last line's end, both included; an edit that only ends at the
   * head's end, or inserts there without a line break, keeps it. With {@code deferred}, a region
   * whose hidden lines meet a group of rows computed again expands too, since those rows lose their
   * wrap. The returned range, in lines of the text before, holds every changed and expanded line
   * and starts and ends at the edge of a span, never inside one. Spans past the range move by the
   * display deltas; the caller places the spans inside it ({@link #place}) once the rows change.
   *
   * @param groups the change's line groups, {@code [0]} to {@code [1]} before, {@code [2]} to
   *     {@code [3]} after
   */
  Edited afterEdit(
      TextContent before,
      TextContent after,
      List<TextEdit> edits,
      List<int[]> groups,
      boolean deferred,
      RowIndex index,
      int charDelta,
      int rowDelta) {
    int from = groups.get(0)[0];
    int to = groups.get(groups.size() - 1)[1];
    if (starts.length == 0) {
      return new Edited(this, from, to);
    }
    EditMap map = new EditMap(before, edits);
    int lineDelta = after.lineCount() - before.lineCount();
    int n = starts.length;
    int[] keptStarts = new int[n];
    int[] keptEnds = new int[n];
    int kept = 0;
    for (int i = 0; i < n; i++) {
      int a = starts[i];
      int b = ends[i];
      if (map.touches(before, a, b) || (deferred && meetsGroup(groups, a + 1, b))) {
        from = Math.min(from, a);
        to = Math.max(to, b);
      } else {
        keptStarts[kept] = a;
        keptEnds[kept] = b;
        kept++;
      }
    }
    int last = before.lineCount() - 1;
    boolean moved = true;
    while (moved) {
      moved = false;
      int k = spanHiding(from);
      if (k >= 0) {
        from = heads[k];
        moved = true;
      }
      k = to < last ? spanHiding(to + 1) : -1;
      if (k >= 0) {
        to = lasts[k];
        moved = true;
      }
    }
    FoldLayer measured =
        of(Arrays.copyOf(keptStarts, kept), Arrays.copyOf(keptEnds, kept), before, index, this);
    int firstEdited = edits.get(0).startLine();
    int lastGroup = groups.get(groups.size() - 1)[1];
    int[] shiftedStarts = new int[kept];
    int[] shiftedEnds = new int[kept];
    for (int i = 0; i < kept; i++) {
      int a = measured.starts[i];
      int shifted;
      if (measured.ends[i] < firstEdited) {
        shifted = a;
      } else if (a > lastGroup) {
        shifted = a + lineDelta;
      } else {
        shifted = after.lineAt(map.map(lineEnd(before, a)));
      }
      shiftedStarts[i] = shifted;
      shiftedEnds[i] = measured.ends[i] + shifted - a;
    }
    int[] shiftedHeads = new int[measured.heads.length];
    int[] shiftedLasts = new int[measured.heads.length];
    FoldLayer out = new FoldLayer(shiftedStarts, shiftedEnds, shiftedHeads, shiftedLasts);
    int i = 0;
    for (int k = 0; k < measured.heads.length; k++) {
      while (measured.starts[i] < measured.heads[k]) {
        i++;
      }
      int shift = shiftedStarts[i] - measured.starts[i];
      shiftedHeads[k] = measured.heads[k] + shift;
      shiftedLasts[k] = measured.lasts[k] + shift;
      boolean past = measured.heads[k] > to;
      out.hiddenStarts[k] = measured.hiddenStarts[k] + (past ? charDelta : 0);
      out.hiddenRows[k] = measured.hiddenRows[k] + (past ? rowDelta : 0);
    }
    System.arraycopy(measured.chars, 0, out.chars, 0, out.chars.length);
    System.arraycopy(measured.rows, 0, out.rows, 0, out.rows.length);
    return new Edited(out, from, to);
  }

  /** Reports whether lines {@code [a, b]} meet a group's lines before the change. */
  private static boolean meetsGroup(List<int[]> groups, int a, int b) {
    for (int[] g : groups) {
      if (g[0] <= b && a <= g[1]) {
        return true;
      }
    }
    return false;
  }

  /** A change's edits, for mapping offsets of the text before to the text after. */
  static final class EditMap {
    private final List<TextEdit> edits;
    private final int[] lastLines;
    private final int[] shifts;

    EditMap(TextContent before, List<TextEdit> edits) {
      this.edits = edits;
      int n = edits.size();
      lastLines = new int[n];
      shifts = new int[n + 1];
      for (int i = 0; i < n; i++) {
        TextEdit e = edits.get(i);
        lastLines[i] = before.lineAt(e.offset() + e.removed().length());
        shifts[i + 1] = shifts[i] + e.inserted().length() - e.removed().length();
      }
    }

    /**
     * Maps an offset: past every edit ending at or before it, an insert at it included; an offset
     * inside a removed range maps to where the edit's text starts.
     */
    int map(int offset) {
      int lo = 0;
      int hi = edits.size();
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        TextEdit e = edits.get(mid);
        if (e.offset() + e.removed().length() <= offset) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      if (lo < edits.size() && edits.get(lo).offset() < offset) {
        return edits.get(lo).offset() + shifts[lo];
      }
      return offset + shifts[lo];
    }

    /** Reports whether an edit touches region {@code (a, b)}'s hidden text; see afterEdit. */
    boolean touches(TextContent before, int a, int b) {
      int lo = 0;
      int hi = edits.size();
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (lastLines[mid] < a) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      if (lo == edits.size() || edits.get(lo).startLine() > b) {
        return false;
      }
      int hs = lineEnd(before, a);
      int he = lineEnd(before, b);
      for (int i = lo; i < edits.size() && edits.get(i).startLine() <= b; i++) {
        TextEdit e = edits.get(i);
        int end = e.offset() + e.removed().length();
        if (end < hs || e.offset() > he) {
          continue;
        }
        boolean keeps =
            end == hs
                && (!e.removed().isEmpty() || e.insertedLines() == 0)
                && !(hs < before.length() && before.charAt(hs) == '\n' && crBefore(before, i));
        if (!keeps) {
          return true;
        }
      }
      return false;
    }

    /**
     * Reports whether a CR ends up directly before edit {@code i}'s end, where it would join an LF
     * there into one line break.
     */
    private boolean crBefore(TextContent before, int i) {
      for (int j = i; j >= 0; j--) {
        TextEdit e = edits.get(j);
        if (!e.inserted().isEmpty()) {
          return e.inserted().charAt(e.inserted().length() - 1) == '\r';
        }
        boolean adjacent =
            j > 0 && edits.get(j - 1).offset() + edits.get(j - 1).removed().length() == e.offset();
        if (!adjacent) {
          return e.offset() > 0 && before.charAt(e.offset() - 1) == '\r';
        }
      }
      return false;
    }
  }
}
