package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * A table's grid lines drawn in {@code SWT.Paint}, over the list view's own painting, with the
 * native lines off. The win32 list view draws {@code LVS_EX_GRIDLINES} in {@code COLOR_3DFACE}, and
 * no public API colors them. Like the native lines, a rule runs along the last pixel row of every
 * row from the header down, empty space included, and the last pixel column of every column, from
 * the header to the bottom. A column resize redraws the table. Confined to the UI thread.
 *
 * <p>Row rules follow each visible item's bounds ({@link #rowRules}), not a multiple of {@link
 * Table#getItemHeight}: at 150 % a row spans 29 px, which no whole number of points gives, so a
 * multiplied height falls half a pixel behind per row and crosses the text by row 12 (measured on
 * Windows 11).
 */
final class Win32GridLines implements Listener {
  /** Column data key: set once the column's resize redraws the table. */
  private static final String HOOKED = Win32GridLines.class.getName() + ".hooked";

  private final Table table;
  private final Color color;

  private Win32GridLines(Table table, Color color) {
    this.table = table;
    this.color = color;
  }

  /**
   * Turns a table's native grid lines off and draws them in a color.
   *
   * @param table the table
   * @param color the line color, which must outlive the table
   */
  static void draw(Table table, Color color) {
    table.setLinesVisible(false);
    table.addListener(SWT.Paint, new Win32GridLines(table, color));
  }

  @Override
  public void handleEvent(Event event) {
    GC gc = event.gc;
    Rectangle area = table.getClientArea();
    int top = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
    int bottom = area.y + area.height;
    gc.setForeground(color);
    gc.setLineWidth(0);
    for (int y : rowRules(table)) {
      gc.drawLine(area.x, y, area.x + area.width, y);
    }
    TableItem sample = table.getItemCount() > 0 ? table.getItem(table.getTopIndex()) : null;
    int x = area.x;
    for (int index : table.getColumnOrder()) {
      TableColumn column = table.getColumn(index);
      hook(column);
      int right;
      if (sample != null) {
        Rectangle cell = sample.getBounds(index);
        right = cell.x + cell.width;
      } else {
        right = x + column.getWidth();
      }
      x = right;
      gc.drawLine(right - 1, top, right - 1, bottom);
    }
  }

  /**
   * Returns the y of each row rule in a table's client area, from the bounds of the items shown.
   *
   * @param table the table
   * @return {@link #rules} of the shown items' tops
   */
  static List<Integer> rowRules(Table table) {
    Rectangle area = table.getClientArea();
    int bottom = area.y + area.height;
    List<Integer> tops = new ArrayList<Integer>();
    int count = table.getItemCount();
    for (int index = table.getTopIndex(); index < count; index++) {
      int y = table.getItem(index).getBounds().y;
      tops.add(y);
      if (y >= bottom) {
        break;
      }
    }
    int top = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
    return rules(tops, table.getItemHeight(), top, bottom);
  }

  /**
   * Returns the y of each row rule: the pixel row above each item's top after the first, then one
   * per pitch below the last item, where the pitch is the last two tops' difference, or the item
   * height with one item. With no item, rules start at {@code top} with the item height as pitch.
   *
   * @param tops the tops of consecutive items, in order
   * @param itemHeight the table's item height
   * @param top the first row's top when there is no item
   * @param bottom the client area's bottom; every rule lies above it
   * @return the rules, top to bottom
   */
  static List<Integer> rules(List<Integer> tops, int itemHeight, int top, int bottom) {
    List<Integer> rules = new ArrayList<Integer>();
    if (itemHeight <= 0) {
      return rules;
    }
    for (int i = 1; i < tops.size() && tops.get(i) - 1 < bottom; i++) {
      rules.add(tops.get(i) - 1);
    }
    int last = top;
    int pitch = itemHeight;
    int size = tops.size();
    if (size > 0) {
      last = tops.get(size - 1);
    }
    if (size > 1 && tops.get(size - 1) > tops.get(size - 2)) {
      pitch = tops.get(size - 1) - tops.get(size - 2);
    }
    for (int y = last + pitch - 1; y < bottom; y += pitch) {
      rules.add(y);
    }
    return rules;
  }

  private void hook(TableColumn column) {
    if (column.getData(HOOKED) != null) {
      return;
    }
    column.setData(HOOKED, Boolean.TRUE);
    column.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!table.isDisposed()) {
              table.redraw();
            }
          }
        });
  }
}
