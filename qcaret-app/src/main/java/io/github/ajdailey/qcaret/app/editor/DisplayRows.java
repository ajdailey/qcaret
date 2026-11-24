package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;

/**
 * The rendered lines a {@link MarkdownDisplayMap} shows as a {@link TableWrap.Row}, ascending, with
 * each row's first display line and display start offset, and the lookups over them. A row of no
 * display lines starts where the line before it ends, at the display line after it. Immutable; the
 * arrays are shared, do not modify them.
 */
final class DisplayRows {
  final int[] lines;
  final TableWrap.Row[] rows;
  final int[] displayLines;
  final int[] displayStarts;
  private final RenderedMarkdown rendered;

  /**
   * Indexes rows.
   *
   * @param rendered the render
   * @param lines ascending rendered lines
   * @param rows one row per line
   */
  DisplayRows(RenderedMarkdown rendered, int[] lines, TableWrap.Row[] rows) {
    this(rendered, lines, rows, new int[lines.length], new int[lines.length]);
    place(0, lines.length, 0, 0);
  }

  private DisplayRows(
      RenderedMarkdown rendered,
      int[] lines,
      TableWrap.Row[] rows,
      int[] displayLines,
      int[] displayStarts) {
    this.rendered = rendered;
    this.lines = lines;
    this.rows = rows;
    this.displayLines = displayLines;
    this.displayStarts = displayStarts;
  }

  /**
   * Sets the first display line and start offset of rows {@code from} up to {@code to}, after rows
   * before them that add {@code extraLines} lines and {@code shift} chars.
   */
  private void place(int from, int to, int extraLines, int shift) {
    for (int i = from; i < to; i++) {
      displayLines[i] = lines[i] + extraLines;
      int rowStart = rendered.lineStart(lines[i]);
      // A row of no display lines drops the line break before it.
      int joined = rows[i].lines() == 0 ? 1 : 0;
      displayStarts[i] = rowStart + shift - joined;
      extraLines += rows[i].lines() - 1;
      shift += rows[i].text().length() - (rowEnd(rendered, lines[i]) - rowStart) - joined;
    }
  }

  /**
   * Returns the index with the rows of consecutive rendered lines replaced. The rows before them
   * are copied as they are and the rows after them shifted, so only the replaced rows are placed
   * again.
   *
   * @param first the first rendered line
   * @param next one row per line from {@code first}; null shows a line as rendered
   * @return the new index
   */
  DisplayRows replace(int first, TableWrap.Row[] next) {
    int before = atLine(first - 1) + 1;
    int after = atLine(first + next.length - 1) + 1;
    int added = 0;
    for (TableWrap.Row row : next) {
      if (row != null) {
        added++;
      }
    }
    int size = lines.length - (after - before) + added;
    int[] newLines = new int[size];
    TableWrap.Row[] newRows = new TableWrap.Row[size];
    int[] newDisplayLines = new int[size];
    int[] newDisplayStarts = new int[size];
    System.arraycopy(lines, 0, newLines, 0, before);
    System.arraycopy(rows, 0, newRows, 0, before);
    System.arraycopy(displayLines, 0, newDisplayLines, 0, before);
    System.arraycopy(displayStarts, 0, newDisplayStarts, 0, before);
    int k = before;
    for (int j = 0; j < next.length; j++) {
      if (next[j] != null) {
        newLines[k] = first + j;
        newRows[k] = next[j];
        k++;
      }
    }
    int tail = lines.length - after;
    System.arraycopy(lines, after, newLines, k, tail);
    System.arraycopy(rows, after, newRows, k, tail);
    DisplayRows out =
        new DisplayRows(rendered, newLines, newRows, newDisplayLines, newDisplayStarts);
    int extraLines = before == 0 ? 0 : extraLinesThrough(before - 1);
    int shift = before == 0 ? 0 : shiftThrough(before - 1);
    out.place(before, k, extraLines, shift);
    if (k > before) {
      extraLines = out.extraLinesThrough(k - 1);
      shift = out.shiftThrough(k - 1);
    }
    int lineDelta = extraLines - (after == 0 ? 0 : extraLinesThrough(after - 1));
    int shiftDelta = shift - (after == 0 ? 0 : shiftThrough(after - 1));
    for (int i = 0; i < tail; i++) {
      newDisplayLines[k + i] = displayLines[after + i] + lineDelta;
      newDisplayStarts[k + i] = displayStarts[after + i] + shiftDelta;
    }
    return out;
  }

  /**
   * Returns the offset after a rendered line's last char, before its line break.
   *
   * @param rendered the render
   * @param line a rendered line
   * @return the offset
   */
  static int rowEnd(RenderedMarkdown rendered, int line) {
    return line + 1 < rendered.lineCount()
        ? rendered.lineStart(line + 1) - 1
        : rendered.text().length();
  }

  /** Display lines added by rows 0 to {@code i}. */
  int extraLinesThrough(int i) {
    return displayLines[i] + rows[i].lines() - 1 - lines[i];
  }

  /** Display chars added by rows 0 to {@code i}. */
  int shiftThrough(int i) {
    return displayStarts[i] + rows[i].text().length() - rowEnd(rendered, lines[i]);
  }

  /** The last row at or before a rendered line, or -1. */
  int atLine(int line) {
    return last(lines, line);
  }

  /** The last row starting at or before a display line, or -1. */
  int atDisplayLine(int displayLine) {
    return last(displayLines, displayLine);
  }

  /** The last row starting at or before a display offset, or -1. */
  int atOffset(int offset) {
    return last(displayStarts, offset);
  }

  /** The last row starting at or before a rendered offset, or -1. */
  int atRenderedOffset(int offset) {
    int lo = 0;
    int hi = lines.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (rendered.lineStart(lines[mid]) <= offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }

  /** The last index whose value is at most {@code key}, or -1. */
  private static int last(int[] sorted, int key) {
    int lo = 0;
    int hi = sorted.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (sorted[mid] <= key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }
}
