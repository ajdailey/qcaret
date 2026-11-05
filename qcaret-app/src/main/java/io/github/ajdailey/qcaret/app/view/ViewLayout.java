package io.github.ajdailey.qcaret.app.view;

/**
 * Pixel and column queries an editor view answers for caret navigation and block selection. Rows
 * are the view's display rows; x and y are pixels in the view's text control. Called on the UI
 * thread.
 */
public interface ViewLayout {
  /**
   * Returns the rows Page Up and Page Down move.
   *
   * @return the whole rows that fit the view, at least 1
   */
  int pageRows();

  /**
   * Returns the row at a y, clamped to the rows.
   *
   * @param y pixels from the control's top
   * @return the row
   */
  int rowAt(int y);

  /**
   * Returns the visual column of the row's model line nearest an x. Inside the row's text the
   * column follows the measured glyph positions; past its end it counts the font's fractional
   * column advance, so a block can reach into virtual space.
   *
   * @param row a display row
   * @param x pixels from the control's left edge
   * @return the column, at least 0, tabs expanded and wide chars counting 2
   */
  int columnAt(int row, int x);
}
