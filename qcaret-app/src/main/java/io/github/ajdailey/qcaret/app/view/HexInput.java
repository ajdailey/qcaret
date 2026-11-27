package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.core.hex.HexRow;

/** Input a {@link HexView} reports to its controller. UI thread. */
public interface HexInput {
  /**
   * Connects the view, which then receives the layout, scroll, selection and status at once.
   *
   * @param view the view, or null when the pane or window is disposed
   */
  void attach(HexView view);

  /**
   * Returns a row to paint. A row not cached yet returns null, and the controller reads it on a
   * worker and calls {@link HexView#redraw} once it lands.
   *
   * @param row a row below the layout's row count
   * @return the row, or null to paint blank
   */
  HexRow row(long row);

  /**
   * Reports the rows that fit the view.
   *
   * @param rows at least 1
   */
  void resized(int rows);

  /**
   * Reports a scroll bar drag.
   *
   * @param selection the scroll bar's selection
   */
  void scrolled(int selection);

  /**
   * Reports a wheel turn or a scroll bar step.
   *
   * @param rows rows to move the top row by; positive scrolls down
   */
  void scrollBy(long rows);

  /**
   * Reports a caret key.
   *
   * @param key the move
   * @param extend true with Shift: the selection extends
   */
  void key(HexKey key, boolean extend);

  /** Reports Tab: the caret moves between the hex and the text column. */
  void switchColumn();

  /**
   * Reports a click on a byte cell.
   *
   * @param offset the byte clicked; the source size for the cell after the last byte
   * @param textColumn true in the text column
   * @param extend true with Shift: the selection extends to the byte
   */
  void clicked(long offset, boolean textColumn, boolean extend);

  /**
   * Reports a drag after {@link #clicked}: the selection runs from the clicked byte to this one,
   * both included.
   *
   * @param offset the byte under the pointer
   */
  void dragged(long offset);

  /** Selects every byte. */
  void selectAll();

  /**
   * Reports that the rows gained or lost the keyboard focus.
   *
   * @param focused true on gain
   */
  void focusChanged(boolean focused);

  /**
   * Copies the selection or the caret offset.
   *
   * @param kind what to copy
   */
  void copy(HexCopy kind);

  /** Opens Go to Offset. */
  void goToOffset();

  /** Opens the find bar with the last pattern. */
  void openFind();

  /**
   * Finds the next or previous match of a pattern from the selection.
   *
   * @param text hex pairs with {@code ??} wildcards, or text
   * @param hex true for a hex pattern
   * @param forward true for Next
   */
  void find(String text, boolean hex, boolean forward);

  /** Reports Esc: a running find or copy stops. */
  void cancel();

  /** Reports the notice bar's button. */
  void noticeAction();
}
