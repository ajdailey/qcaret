package io.github.ajdailey.qcaret.core.markdown;

import java.util.List;

/**
 * A GFM table: one rendered line per row, header row first, cells separated by a tab. A tab in cell
 * text renders as a space and a line break in a cell as a space, so a row stays one line.
 *
 * @param firstLine the rendered line of the header row
 * @param alignments one per column
 * @param cells per row, {@code start, end} offset pairs, one pair per column; a row the source left
 *     short has empty pairs at the line's end. Shared, not copied: do not modify
 */
public record MarkdownTable(int firstLine, List<Alignment> alignments, int[][] cells) {
  /** A column's alignment from the delimiter row. */
  public enum Alignment {
    /** No colon. */
    NONE,
    /** {@code :---}. */
    LEFT,
    /** {@code :---:}. */
    CENTER,
    /** {@code ---:}. */
    RIGHT
  }

  /** Copies the alignments. */
  public MarkdownTable {
    alignments = List.copyOf(alignments);
  }

  /**
   * Counts the rows, the header row included.
   *
   * @return at least 1
   */
  public int rowCount() {
    return cells.length;
  }
}
