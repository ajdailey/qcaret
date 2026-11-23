package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.List;

/**
 * The rendered lines a {@link MarkdownDisplayMap} shows as a {@link TableWrap.Row}, ascending, with
 * each row's first display line and display start offset, and the lookups over them. Immutable; the
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
    this.rendered = rendered;
    this.lines = lines;
    this.rows = rows;
    displayLines = new int[lines.length];
    displayStarts = new int[lines.length];
    int extraLines = 0;
    int shift = 0;
    for (int i = 0; i < lines.length; i++) {
      displayLines[i] = lines[i] + extraLines;
      int rowStart = rendered.lineStart(lines[i]);
      displayStarts[i] = rowStart + shift;
      extraLines += rows[i].lines() - 1;
      shift += rows[i].text().length() - (rowEnd(rendered, lines[i]) - rowStart);
    }
  }

  /**
   * Returns the index with the rows of consecutive rendered lines replaced.
   *
   * @param first the first rendered line
   * @param next one row per line from {@code first}; null shows a line as rendered
   * @return the new index
   */
  DisplayRows replace(int first, TableWrap.Row[] next) {
    int last = first + next.length - 1;
    List<Integer> newLines = new ArrayList<Integer>();
    List<TableWrap.Row> newRows = new ArrayList<TableWrap.Row>();
    int k = 0;
    while (k < lines.length && lines[k] < first) {
      newLines.add(lines[k]);
      newRows.add(rows[k]);
      k++;
    }
    for (int j = 0; j < next.length; j++) {
      if (next[j] != null) {
        newLines.add(first + j);
        newRows.add(next[j]);
      }
    }
    while (k < lines.length && lines[k] <= last) {
      k++;
    }
    while (k < lines.length) {
      newLines.add(lines[k]);
      newRows.add(rows[k]);
      k++;
    }
    int[] flat = new int[newLines.size()];
    for (int i = 0; i < flat.length; i++) {
      flat[i] = newLines.get(i);
    }
    return new DisplayRows(rendered, flat, newRows.toArray(new TableWrap.Row[0]));
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
