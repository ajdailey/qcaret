package io.github.ajdailey.qcaret.core.hex;

/**
 * The visible rows of a hex view and their mapping onto an {@code int} scroll bar. Rows are {@code
 * long}; a scroll bar is not. Not thread-safe: the UI thread owns an instance.
 *
 * <p>Let {@code maxTop = max(0, rowCount - rows)}. While {@code maxTop <= SCROLLBAR_RANGE} the
 * scroll bar is one unit per row with a thumb of {@code rows}. Above that it is scaled: selection
 * {@code round(top / maxTop * SCROLLBAR_RANGE)}, and the inverse for a thumb drag, in {@code
 * double}, which is exact at both ends for any {@code maxTop < 2^53}. Wheel, keys and Go to Offset
 * set {@link #topRow} directly; only a thumb drag goes through {@link #setScrollbarSelection}.
 */
public final class HexViewport {
  /** Scroll bar units in scaled mode. */
  public static final int SCROLLBAR_RANGE = 1 << 30;

  private long size;
  private int bytesPerRow;
  private int rows = 1;
  private long topRow;

  /**
   * Creates a viewport over an empty source.
   *
   * @param bytesPerRow one of {@link HexRows#WIDTHS}
   * @throws IllegalArgumentException for any other width
   */
  public HexViewport(int bytesPerRow) {
    this.bytesPerRow = HexRows.checkWidth(bytesPerRow);
  }

  /**
   * Returns the source size as last set.
   *
   * @return bytes
   */
  public long size() {
    return size;
  }

  /**
   * Returns the bytes per row.
   *
   * @return one of {@link HexRows#WIDTHS}
   */
  public int bytesPerRow() {
    return bytesPerRow;
  }

  /**
   * Returns the row count.
   *
   * @return {@code ceil(size / bytesPerRow)}
   */
  public long rowCount() {
    return HexRows.rowCount(size, bytesPerRow);
  }

  /**
   * Returns the first visible row.
   *
   * @return a row from 0 to {@link #maxTop}
   */
  public long topRow() {
    return topRow;
  }

  /**
   * Returns the first visible byte.
   *
   * @return {@code topRow * bytesPerRow}
   */
  public long topOffset() {
    return topRow * bytesPerRow;
  }

  /**
   * Returns the rows that fit the view.
   *
   * @return at least 1
   */
  public int rows() {
    return rows;
  }

  /**
   * Returns the last top row that still fills the view.
   *
   * @return {@code max(0, rowCount - rows)}
   */
  public long maxTop() {
    return Math.max(0, rowCount() - rows);
  }

  /**
   * Counts the visible rows that show bytes.
   *
   * @return {@link #rows}, fewer at the end of a short source
   */
  public int visibleRows() {
    return (int) Math.max(0, Math.min(rows, rowCount() - topRow));
  }

  /**
   * Sets the source size; the top row stays unless it passes the new end.
   *
   * @param size bytes; a negative value counts as 0
   */
  public void setSize(long size) {
    this.size = Math.max(0, size);
    topRow = clampTop(topRow);
  }

  /**
   * Sets the bytes per row, keeping the top row's first byte in the top row.
   *
   * @param bytesPerRow one of {@link HexRows#WIDTHS}
   * @throws IllegalArgumentException for any other width
   */
  public void setBytesPerRow(int bytesPerRow) {
    long top = topOffset();
    this.bytesPerRow = HexRows.checkWidth(bytesPerRow);
    topRow = clampTop(top / bytesPerRow);
  }

  /**
   * Sets the rows that fit the view.
   *
   * @param rows rows; at least 1 is kept
   */
  public void resize(int rows) {
    this.rows = Math.max(1, rows);
    topRow = clampTop(topRow);
  }

  /**
   * Puts a row at the top, or as near as the end allows.
   *
   * @param row a row
   */
  public void scrollToRow(long row) {
    topRow = clampTop(row);
  }

  /**
   * Moves the top row.
   *
   * @param delta positive scrolls down
   */
  public void scrollBy(long delta) {
    long target = topRow + delta;
    if (delta > 0 && target < topRow) {
      target = Long.MAX_VALUE;
    }
    topRow = clampTop(target);
  }

  /**
   * Scrolls the least distance that shows a row.
   *
   * @param row a row
   */
  public void ensureVisible(long row) {
    if (row < topRow) {
      topRow = clampTop(row);
    } else if (row >= topRow + rows) {
      topRow = clampTop(row - rows + 1);
    }
  }

  /**
   * Scrolls the least distance that shows a byte.
   *
   * @param offset a byte offset
   */
  public void ensureOffsetVisible(long offset) {
    ensureVisible(offset / bytesPerRow);
  }

  /**
   * Reports whether the scroll bar is scaled.
   *
   * @return true when {@link #maxTop} exceeds {@link #SCROLLBAR_RANGE}
   */
  public boolean scaled() {
    return maxTop() > SCROLLBAR_RANGE;
  }

  /**
   * Returns the scroll bar maximum.
   *
   * @return {@code maxTop + rows} unscaled, {@code SCROLLBAR_RANGE + thumb} scaled
   */
  public int scrollbarMaximum() {
    if (scaled()) {
      return SCROLLBAR_RANGE + scrollbarThumb();
    }
    return (int) Math.min(Integer.MAX_VALUE, maxTop() + rows);
  }

  /**
   * Returns the scroll bar thumb.
   *
   * @return {@code rows} unscaled, {@code max(1, rows * SCROLLBAR_RANGE / maxTop)} scaled
   */
  public int scrollbarThumb() {
    if (scaled()) {
      return (int) Math.max(1, (long) rows * SCROLLBAR_RANGE / maxTop());
    }
    return rows;
  }

  /**
   * Returns the scroll bar selection for the top row.
   *
   * @return 0 to {@code scrollbarMaximum() - scrollbarThumb()}
   */
  public int scrollbarSelection() {
    long maxTop = maxTop();
    if (maxTop > SCROLLBAR_RANGE) {
      return (int) Math.round((double) topRow / maxTop * SCROLLBAR_RANGE);
    }
    return (int) topRow;
  }

  /**
   * Sets the top row from a scroll bar selection, for a thumb drag.
   *
   * @param selection the scroll bar's selection; clamped to its range
   */
  public void setScrollbarSelection(int selection) {
    long maxTop = maxTop();
    if (maxTop > SCROLLBAR_RANGE) {
      int clamped = Math.max(0, Math.min(SCROLLBAR_RANGE, selection));
      topRow = clampTop(Math.round((double) clamped / SCROLLBAR_RANGE * maxTop));
    } else {
      topRow = clampTop(selection);
    }
  }

  private long clampTop(long row) {
    return Math.max(0, Math.min(row, maxTop()));
  }
}
