package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.Columns;

/**
 * Vertical caret moves over a {@link DisplayProjection}'s rows. A caret keeps a preferred visual
 * column, counted from its row's start as the rows are laid out, so a run of Up and Down keys
 * returns to the column it left from across shorter rows. A move past the first row goes to the
 * text's start, past the last row to its end.
 */
public final class CaretNavigation {
  private CaretNavigation() {}

  /**
   * Returns where a caret lands some rows up or down.
   *
   * @param projection the view's rows
   * @param caret the caret's model offset
   * @param preferred the preferred column, or -1 to take the caret's own
   * @param rows rows to move, negative for up
   * @param tabWidth columns per tab stop
   * @return {model offset, preferred column}
   */
  public static int[] vertical(
      DisplayProjection projection, int caret, int preferred, int rows, int tabWidth) {
    int display = projection.toDisplay(caret);
    int row = projection.rowAt(display);
    String rowText = projection.rowText(row);
    int index = Math.min(display - projection.rowStart(row), rowText.length());
    int column = preferred >= 0 ? preferred : Columns.column(rowText, index, tabWidth);
    int target = row + rows;
    if (target < 0) {
      return new int[] {0, column};
    }
    if (target >= projection.rowCount()) {
      return new int[] {projection.modelLength(), column};
    }
    String text = projection.rowText(target);
    int at = Columns.index(text, column, tabWidth, false);
    boolean continued =
        target + 1 < projection.rowCount()
            && projection.lineOfRow(target + 1) == projection.lineOfRow(target);
    if (continued && at == text.length() && at > 0) {
      at -= at > 1 && Character.isSurrogatePair(text.charAt(at - 2), text.charAt(at - 1)) ? 2 : 1;
    }
    return new int[] {projection.toModel(projection.rowStart(target) + at), column};
  }

  /**
   * Returns the visual column of an offset in its model line.
   *
   * @param text the text
   * @param offset the offset
   * @param tabWidth columns per tab stop
   * @return the column
   */
  public static int column(TextContent text, int offset, int tabWidth) {
    LineView line = text.lineView(text.lineAt(offset), new LineView());
    return Columns.column(line, Math.min(offset - line.start(), line.length()), tabWidth);
  }

  /**
   * Returns the column one char left or right of a column on a model line: the edge of the char
   * that covers it, or one column further in the virtual space past the line's end.
   *
   * @param text the text
   * @param line the line
   * @param column the column
   * @param right true to step right
   * @param tabWidth columns per tab stop
   * @return the column, at least 0
   */
  public static int step(TextContent text, int line, int column, boolean right, int tabWidth) {
    LineView view = text.lineView(line, new LineView());
    int end = Columns.column(view, view.length(), tabWidth);
    if (right ? column >= end : column > end) {
      return Math.max(0, column + (right ? 1 : -1));
    }
    if (right) {
      return Columns.column(view, Columns.index(view, column + 1, tabWidth, true), tabWidth);
    }
    return column == 0
        ? 0
        : Columns.column(view, Columns.index(view, column - 1, tabWidth, false), tabWidth);
  }
}
