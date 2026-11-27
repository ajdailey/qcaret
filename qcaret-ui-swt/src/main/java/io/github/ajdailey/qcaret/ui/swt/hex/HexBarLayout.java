package io.github.ajdailey.qcaret.ui.swt.hex;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;

/**
 * The hex find bar's layout: the controls left to right, starting a new row where the next one does
 * not fit, then the message on its own row, wrapped to the bar's width. A control wider than the
 * bar is narrowed to it; a {@link RowData} width sets a control's preferred width. A hidden message
 * takes no room, and the message never widens the bar.
 */
final class HexBarLayout extends Layout {
  /** Left and right margin. */
  static final int MARGIN_WIDTH = 5;

  private static final int MARGIN_HEIGHT = 2;
  private static final int SPACING = 5;

  private final Control message;

  HexBarLayout(Control message) {
    this.message = message;
  }

  /** Returns the narrowest width at which every control keeps its preferred width. */
  int minimumWidth(Composite bar) {
    int widest = 0;
    for (Control child : bar.getChildren()) {
      if (child != message) {
        widest = Math.max(widest, preferred(child).x);
      }
    }
    return widest + 2 * MARGIN_WIDTH;
  }

  @Override
  protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
    int width = wHint == SWT.DEFAULT ? SWT.DEFAULT : Math.max(0, wHint - 2 * MARGIN_WIDTH);
    Point used = place(composite, width, 0, 0, false);
    return new Point(
        wHint == SWT.DEFAULT ? used.x + 2 * MARGIN_WIDTH : wHint,
        hHint == SWT.DEFAULT ? used.y + 2 * MARGIN_HEIGHT : hHint);
  }

  @Override
  protected void layout(Composite composite, boolean flushCache) {
    Rectangle area = composite.getClientArea();
    place(
        composite,
        Math.max(0, area.width - 2 * MARGIN_WIDTH),
        area.x + MARGIN_WIDTH,
        area.y + MARGIN_HEIGHT,
        true);
  }

  /**
   * Places or measures the children.
   *
   * @param width the width inside the margins, or {@link SWT#DEFAULT} for one row
   * @return the width and height used
   */
  private Point place(Composite composite, int width, int left, int top, boolean move) {
    List<Control> row = new ArrayList<Control>();
    List<Point> sizes = new ArrayList<Point>();
    int x = 0;
    int y = 0;
    int rowHeight = 0;
    int widest = 0;
    for (Control child : composite.getChildren()) {
      if (child == message) {
        continue;
      }
      Point size = preferred(child);
      if (width != SWT.DEFAULT && size.x > width) {
        size.x = width;
      }
      if (!row.isEmpty() && width != SWT.DEFAULT && x + SPACING + size.x > width) {
        place(row, sizes, left, top + y, rowHeight, move);
        widest = Math.max(widest, x);
        y += rowHeight + MARGIN_HEIGHT;
        row.clear();
        sizes.clear();
        x = 0;
        rowHeight = 0;
      }
      x += (row.isEmpty() ? 0 : SPACING) + size.x;
      rowHeight = Math.max(rowHeight, size.y);
      row.add(child);
      sizes.add(size);
    }
    place(row, sizes, left, top + y, rowHeight, move);
    widest = Math.max(widest, x);
    y += rowHeight;
    if (message.getVisible()) {
      int messageWidth = width == SWT.DEFAULT ? widest : width;
      int height = message.computeSize(messageWidth, SWT.DEFAULT).y;
      y += MARGIN_HEIGHT;
      if (move) {
        message.setBounds(left, top + y, messageWidth, height);
      }
      y += height;
    }
    return new Point(widest, y);
  }

  private static void place(
      List<Control> row, List<Point> sizes, int left, int top, int height, boolean move) {
    if (!move) {
      return;
    }
    int x = left;
    for (int i = 0; i < row.size(); i++) {
      Point size = sizes.get(i);
      row.get(i).setBounds(x, top + (height - size.y) / 2, size.x, size.y);
      x += size.x + SPACING;
    }
  }

  private static Point preferred(Control child) {
    int hint =
        child.getLayoutData() instanceof RowData data && data.width != SWT.DEFAULT
            ? data.width
            : SWT.DEFAULT;
    return child.computeSize(hint, SWT.DEFAULT);
  }
}
