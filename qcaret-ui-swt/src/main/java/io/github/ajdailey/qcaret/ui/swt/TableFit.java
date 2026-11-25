package io.github.ajdailey.qcaret.ui.swt;

import java.util.Arrays;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Control;
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
  /**
   * Points added to a header text's width for the padding SWT's win32 custom-drawn header leaves (6
   * pixels each side), which clips the text without an ellipsis.
   */
  static final int HEADER_PADDING = 16;

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
   * the others share the rest in proportion to their widths at this call, each at least its header
   * text's width while the rest holds every such minimum, within {@link #room}.
   *
   * @param table the table, with its columns
   */
  static void spread(final Table table) {
    final int[] weights = new int[table.getColumnCount()];
    final int[] minima = new int[weights.length];
    GC gc = new GC(table);
    try {
      for (int i = 1; i < weights.length; i++) {
        weights[i] = Math.max(1, table.getColumn(i).getWidth());
        minima[i] = gc.textExtent(table.getColumn(i).getText()).x + HEADER_PADDING;
      }
    } finally {
      gc.dispose();
    }
    table.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            spread(table, weights, minima);
          }
        });
  }

  /**
   * Sets the widths of every column after the first to fill the table's width, narrowing columns
   * before widening any ({@link #order}).
   *
   * @param table the table
   * @param weights each column's share, from index 1
   * @param minima each column's smallest width, from index 1, kept while the rest holds them all
   */
  static void spread(Table table, int[] weights, int[] minima) {
    if (table.isDisposed() || weights.length < 2) {
      return;
    }
    int[] widths = widths(table, weights, minima);
    int[] current = new int[widths.length];
    for (int i = 1; i < widths.length; i++) {
      current[i] = table.getColumn(i).getWidth();
    }
    for (int i : order(current, widths)) {
      table.getColumn(i).setWidth(widths[i]);
    }
  }

  /**
   * Returns the widths {@link #spread(Table, int[], int[])} gives every column after the first at
   * the table's current size.
   *
   * @param table the table, with its columns
   * @param weights each column's share, from index 1
   * @param minima each column's smallest width, from index 1
   * @return each column's width, from index 1
   */
  static int[] widths(Table table, int[] weights, int[] minima) {
    ScrollBar bar = table.getVerticalBar();
    int hidden = bar != null && !bar.getVisible() ? bar.getSize().x : 0;
    int width = room(table.getClientArea().width, hidden, zoom(table) % 100 != 0);
    return shares(Math.max(0, width - table.getColumn(0).getWidth()), weights, minima);
  }

  /**
   * Returns the width the columns may fill: the client width less a hidden vertical scroll bar, so
   * rows added later do not bring a horizontal one, and less one point at a fractional zoom, where
   * each column's width rounds to pixels on its own.
   *
   * @param client the client area's width
   * @param hiddenBar the vertical scroll bar's width while it is hidden, else 0
   * @param fractional whether the zoom is not a whole multiple of 100
   * @return the width
   */
  static int room(int client, int hiddenBar, boolean fractional) {
    return client - hiddenBar - (fractional ? 1 : 0);
  }

  /**
   * Returns the zoom SWT scales a control's points by: a numeric {@code swt.autoScale} value, else
   * the control's monitor zoom.
   *
   * @param control the control
   * @return the zoom in percent
   */
  static int zoom(Control control) {
    String scale = System.getProperty("swt.autoScale");
    if (scale != null) {
      try {
        return Integer.parseInt(scale.trim());
      } catch (NumberFormatException named) {
        return control.getMonitor().getZoom();
      }
    }
    return control.getMonitor().getZoom();
  }

  /**
   * Returns the columns from index 1 in the order to set their widths: each column that narrows
   * before any that widens, so the columns' total never passes the larger of the two totals. A
   * total past the client area for a moment makes the win32 list view show a horizontal scroll bar,
   * and the vertical one that bar forces, which stay after the columns fit.
   *
   * @param current each column's width now, from index 1
   * @param widths each column's new width, from index 1
   * @return the column indexes whose width changes, narrowing ones first
   */
  static int[] order(int[] current, int[] widths) {
    int[] order = new int[widths.length];
    int count = 0;
    for (int i = 1; i < widths.length; i++) {
      if (widths[i] < current[i]) {
        order[count++] = i;
      }
    }
    for (int i = 1; i < widths.length; i++) {
      if (widths[i] > current[i]) {
        order[count++] = i;
      }
    }
    return Arrays.copyOf(order, count);
  }

  /**
   * Divides a width among columns 1 and up in proportion to their weights. When the width holds
   * every minimum, a column whose share falls below its minimum takes the minimum and the others
   * share what is left. The widths sum to the width.
   *
   * @param rest the width to divide
   * @param weights each column's share, from index 1
   * @param minima each column's smallest width, from index 1
   * @return each column's width, from index 1
   */
  static int[] shares(int rest, int[] weights, int[] minima) {
    int[] widths = new int[weights.length];
    boolean[] pinned = new boolean[weights.length];
    int needed = 0;
    for (int i = 1; i < weights.length; i++) {
      needed += minima[i];
    }
    boolean pinning = rest >= needed;
    boolean changed = true;
    while (changed) {
      changed = false;
      int free = rest;
      int total = 0;
      int last = 0;
      for (int i = 1; i < weights.length; i++) {
        if (pinned[i]) {
          free -= minima[i];
        } else {
          total += weights[i];
          last = i;
        }
      }
      int given = 0;
      for (int i = 1; i < weights.length; i++) {
        if (pinned[i]) {
          widths[i] = minima[i];
        } else {
          widths[i] = i == last ? free - given : free * weights[i] / total;
          given += widths[i];
          if (pinning && widths[i] < minima[i]) {
            pinned[i] = true;
            changed = true;
          }
        }
      }
    }
    return widths;
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
