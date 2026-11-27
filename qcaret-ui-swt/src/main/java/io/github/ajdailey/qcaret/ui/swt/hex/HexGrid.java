package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.core.hex.HexRows;

/**
 * Cell geometry of a hex view: a header row, then one row per {@link HexRows} row, each {@link
 * #lineHeight} high. Column {@code c} starts at {@code PAD + round(c * advance)}, so every cell
 * sits at its own x and a fractional advance does not accumulate across a row. Immutable and free
 * of SWT types.
 */
final class HexGrid {
  /** Pixels left of the offset column and right of the text column. */
  static final int PAD = 4;

  private final HexRows rows;
  private final double advance;
  private final int lineHeight;

  /**
   * Creates the geometry.
   *
   * @param rows the layout
   * @param advance pixels per column, above 0
   * @param lineHeight pixels per row
   */
  HexGrid(HexRows rows, double advance, int lineHeight) {
    this.rows = rows;
    this.advance = Math.max(0.5, advance);
    this.lineHeight = Math.max(1, lineHeight);
  }

  HexRows rows() {
    return rows;
  }

  int lineHeight() {
    return lineHeight;
  }

  /**
   * Returns a column's left edge.
   *
   * @param column a column of {@link HexRows}
   * @return {@code PAD + round(column * advance)}
   */
  int x(int column) {
    return PAD + (int) Math.round(column * advance);
  }

  /** Returns the pixel width a full row needs. */
  int rowWidth() {
    return x(rows.columns()) + PAD;
  }

  /**
   * Returns the y of a visible row.
   *
   * @param index 0 for the first row under the header
   * @return pixels from the top
   */
  int y(int index) {
    return (index + 1) * lineHeight;
  }

  /**
   * Returns the rows that fit a height under the header.
   *
   * @param height pixels
   * @return at least 1
   */
  int visibleRows(int height) {
    return Math.max(1, height / lineHeight - 1);
  }

  /**
   * Returns the byte under a point, clamped into the rows: left of the hex column is a row's first
   * byte, right of a row's last byte its last byte, below the last row the size.
   *
   * @param x pixels from the left
   * @param y pixels from the top; the header maps to the top row
   * @param topRow the first visible row
   * @return the byte offset, from 0 to the size
   */
  long offsetAt(int x, int y, long topRow) {
    long row = topRow + Math.max(0, y / lineHeight - 1);
    if (row >= rows.rowCount()) {
      return rows.size();
    }
    return Math.min(rows.size(), rows.rowStart(row) + indexAt(x));
  }

  /**
   * Reports whether a point lies in the text column.
   *
   * @param x pixels from the left
   * @return true right of the middle of the gap between the hex and text columns
   */
  boolean inTextColumn(int x) {
    int hexEnd = rows.hexColumn(rows.bytesPerRow() - 1) + 2;
    return 2 * (x - PAD) >= Math.round((hexEnd + rows.textColumn(0)) * advance);
  }

  /** Returns the index in its row of the byte whose cell an x falls in. */
  private int indexAt(int x) {
    int last = rows.bytesPerRow() - 1;
    if (inTextColumn(x)) {
      int column = (int) Math.floor((x - PAD) / advance) - rows.textColumn(0);
      return Math.max(0, Math.min(last, column));
    }
    int index = 0;
    for (int i = 1; i <= last; i++) {
      // The space before a byte's digits belongs to it.
      if (x >= x(rows.hexColumn(i) - 1)) {
        index = i;
      }
    }
    return index;
  }
}
