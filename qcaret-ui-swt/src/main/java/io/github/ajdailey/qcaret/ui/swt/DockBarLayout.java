package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;

/**
 * Lays out a dock's button row and summary within the dock's width: the children left to right at
 * their preferred sizes, a child that does not fit after the others starting a new row, and a child
 * wider than the whole width taking that width, so a {@code SWT.WRAP} label wraps its text. Each
 * child is centered in its row. A width of {@code SWT.DEFAULT} puts every child on one row.
 */
final class DockBarLayout extends Layout {
  /** Space above and below the rows. */
  static final int MARGIN = 2;

  /** Space between children in a row, and between rows. */
  static final int SPACING = 4;

  @Override
  protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
    int width = wHint == SWT.DEFAULT ? SWT.DEFAULT : Math.max(0, wHint);
    Rectangle[] bounds = place(composite.getChildren(), width);
    int right = 0;
    int bottom = 0;
    for (Rectangle bound : bounds) {
      right = Math.max(right, bound.x + bound.width);
      bottom = Math.max(bottom, bound.y + bound.height);
    }
    return new Point(
        wHint == SWT.DEFAULT ? right : wHint, hHint == SWT.DEFAULT ? bottom + MARGIN : hHint);
  }

  @Override
  protected void layout(Composite composite, boolean flushCache) {
    Rectangle area = composite.getClientArea();
    Control[] children = composite.getChildren();
    Rectangle[] bounds = place(children, area.width);
    for (int i = 0; i < children.length; i++) {
      Rectangle bound = bounds[i];
      children[i].setBounds(area.x + bound.x, area.y + bound.y, bound.width, bound.height);
    }
  }

  /** Returns each child's bounds for a width, or on one row for {@code SWT.DEFAULT}. */
  private static Rectangle[] place(Control[] children, int width) {
    Rectangle[] bounds = new Rectangle[children.length];
    int x = 0;
    int y = MARGIN;
    int rowHeight = 0;
    int rowStart = 0;
    for (int i = 0; i < children.length; i++) {
      Point size = children[i].computeSize(SWT.DEFAULT, SWT.DEFAULT);
      if (width != SWT.DEFAULT) {
        if (i > rowStart && x + size.x > width) {
          center(bounds, rowStart, i, rowHeight);
          y += rowHeight + SPACING;
          x = 0;
          rowHeight = 0;
          rowStart = i;
        }
        if (size.x > width) {
          size = new Point(width, children[i].computeSize(width, SWT.DEFAULT).y);
        }
      }
      bounds[i] = new Rectangle(x, y, size.x, size.y);
      x += size.x + SPACING;
      rowHeight = Math.max(rowHeight, size.y);
    }
    center(bounds, rowStart, children.length, rowHeight);
    return bounds;
  }

  private static void center(Rectangle[] bounds, int from, int to, int rowHeight) {
    for (int i = from; i < to; i++) {
      bounds[i].y += (rowHeight - bounds[i].height) / 2;
    }
  }
}
