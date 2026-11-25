package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Fits a table and its columns: the dialog width that shows every packed column, a tool tip with
 * the full text of a cell still cut off, and a dock table's columns spread over its width.
 */
final class TableFit {
  private TableFit() {}

  /**
   * Returns the dialog's client width that shows every packed column of its table, the whole dialog
   * within the screen: the width {@link Dialogs#open} takes.
   *
   * @param shell the dialog, laid out by a {@code GridLayout}
   * @param table the table, with {@link GridData}
   * @param minimum the smallest client width, or {@code SWT.DEFAULT}
   * @param screen the area the dialog must fit in
   * @return the client width
   */
  static int dialogWidth(Shell shell, Table table, int minimum, Rectangle screen) {
    int columns = 0;
    for (int i = 0; i < table.getColumnCount(); i++) {
      columns += table.getColumn(i).getWidth();
    }
    GridData data = (GridData) table.getLayoutData();
    int hint = data.widthHint;
    data.widthHint = Math.max(hint, table.computeTrim(0, 0, columns, 0).width);
    int trim = shell.computeTrim(0, 0, 0, 0).width;
    int width;
    try {
      width = Math.max(minimum, shell.computeSize(SWT.DEFAULT, SWT.DEFAULT).x - trim);
    } finally {
      data.widthHint = hint;
    }
    return Math.min(width, screen.width - trim);
  }

  /**
   * Keeps a table's columns within its width at each resize: the first column keeps its width and
   * the others share the rest in proportion to their widths at this call. The share leaves room for
   * a hidden vertical scroll bar, so rows added later do not bring a horizontal one, and one point
   * at a fractional zoom, where each column's width rounds on its own.
   *
   * @param table the table, with its columns
   */
  static void spread(final Table table) {
    final int[] weights = new int[table.getColumnCount()];
    for (int i = 1; i < weights.length; i++) {
      weights[i] = Math.max(1, table.getColumn(i).getWidth());
    }
    table.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            spread(table, weights);
          }
        });
  }

  /**
   * Sets the widths of every column after the first to fill the table's width.
   *
   * @param table the table
   * @param weights each column's share, from index 1
   */
  static void spread(Table table, int[] weights) {
    if (table.isDisposed() || weights.length < 2) {
      return;
    }
    int width = table.getClientArea().width;
    ScrollBar bar = table.getVerticalBar();
    if (bar != null && !bar.getVisible()) {
      width -= bar.getSize().x;
    }
    if (table.getMonitor().getZoom() % 100 != 0) {
      width--;
    }
    int rest = Math.max(0, width - table.getColumn(0).getWidth());
    int total = 0;
    for (int i = 1; i < weights.length; i++) {
      total += weights[i];
    }
    int given = 0;
    for (int i = 1; i < weights.length; i++) {
      int share = i == weights.length - 1 ? rest - given : rest * weights[i] / total;
      table.getColumn(i).setWidth(share);
      given += share;
    }
  }

  /**
   * Gives a cell whose text is cut off a tool tip with the full text while the pointer is over it.
   *
   * @param table the table
   */
  static void cellTips(final Table table) {
    Listener tips =
        new Listener() {
          private TableItem item;
          private int column = -1;

          @Override
          public void handleEvent(Event event) {
            TableItem over = null;
            int at = -1;
            if (event.type == SWT.MouseMove) {
              over = table.getItem(new Point(event.x, event.y));
              at = over == null ? -1 : column(over, event.x);
            }
            if (over == item && at == column) {
              return;
            }
            item = over;
            column = at;
            table.setToolTipText(at < 0 ? null : clippedText(over, at));
          }
        };
    table.addListener(SWT.MouseMove, tips);
    table.addListener(SWT.MouseExit, tips);
  }

  /**
   * Returns a cell's text when the table shows less than all of it.
   *
   * @param item the row
   * @param column the column index
   * @return the text, or null when it shows whole
   */
  static String clippedText(TableItem item, int column) {
    String text = item.getText(column);
    if (text.isEmpty()) {
      return null;
    }
    Table table = item.getParent();
    GC gc = new GC(table);
    try {
      gc.setFont(item.getFont(column));
      return gc.textExtent(text).x > shownWidth(item, column) ? text : null;
    } finally {
      gc.dispose();
    }
  }

  /**
   * Returns the width of a cell's text area inside the table's client area.
   *
   * @param item the row
   * @param column the column index
   * @return the width in pixels, 0 when the cell is out of view
   */
  static int shownWidth(TableItem item, int column) {
    Rectangle text = item.getTextBounds(column);
    Rectangle client = item.getParent().getClientArea();
    int right = Math.min(text.x + text.width, client.x + client.width);
    return Math.max(0, right - Math.max(text.x, client.x));
  }

  /** Returns the index of the column holding an x position on a row, or -1. */
  private static int column(TableItem item, int x) {
    for (int i = 0; i < item.getParent().getColumnCount(); i++) {
      Rectangle cell = item.getBounds(i);
      if (x >= cell.x && x < cell.x + cell.width) {
        return i;
      }
    }
    return -1;
  }
}
