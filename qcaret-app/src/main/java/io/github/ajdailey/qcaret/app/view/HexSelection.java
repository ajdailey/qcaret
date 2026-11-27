package io.github.ajdailey.qcaret.app.view;

/**
 * A hex view's caret and selection. The caret is a position from 0 to the source size, drawn on the
 * byte at that position, or after the last byte at the size. The selection is the bytes from {@link
 * #start} up to {@link #end}, empty when the anchor is the caret.
 *
 * @param anchor where the selection started
 * @param caret the caret
 * @param textColumn true when the caret is in the text column, false in the hex column
 */
public record HexSelection(long anchor, long caret, boolean textColumn) {
  /**
   * Returns the first selected byte.
   *
   * @return the lesser of anchor and caret
   */
  public long start() {
    return Math.min(anchor, caret);
  }

  /**
   * Returns the position after the last selected byte.
   *
   * @return the greater of anchor and caret
   */
  public long end() {
    return Math.max(anchor, caret);
  }

  /**
   * Returns the selected byte count.
   *
   * @return 0 when nothing is selected
   */
  public long length() {
    return end() - start();
  }
}
