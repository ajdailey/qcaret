package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Paints the modified-line marks in the {@value #WIDTH} px left of the gap between the gutter and
 * the text: a bar on every row of an added line in the added color and of a changed line in the
 * changed color, and for removed lines a triangle in the removed color pointing right, centered on
 * the top edge of the line after them or the bottom edge of the last line. The marks are the last
 * {@link LineChanges} shown, kept until the next, so after an edit they lag by one computation.
 * Only the visible rows are read. Confined to the UI thread.
 */
final class LineChangePainter {
  /** The mark column's width in pixels. */
  static final int WIDTH = 3;

  private boolean shown;
  private LineChanges changes = LineChanges.NONE;
  private Color added;
  private Color changed;
  private Color removed;

  /**
   * Shows or hides the marks; hidden, they are dropped.
   *
   * @param value true to show them
   * @return true when it changed
   */
  boolean setShown(boolean value) {
    if (value == shown) {
      return false;
    }
    shown = value;
    if (!shown) {
      changes = LineChanges.NONE;
    }
    return true;
  }

  /**
   * Reports whether the marks show.
   *
   * @return true while shown
   */
  boolean shown() {
    return shown;
  }

  /**
   * Replaces the marks.
   *
   * @param value the marks
   */
  void setChanges(LineChanges value) {
    changes = value;
  }

  /**
   * Returns the marks.
   *
   * @return the last marks set, {@link LineChanges#NONE} while hidden
   */
  LineChanges changes() {
    return changes;
  }

  /**
   * Sets the colors.
   *
   * @param addedColor an added line's bar
   * @param changedColor a changed line's bar
   * @param removedColor the removed lines' triangle
   */
  void setColors(Color addedColor, Color changedColor, Color removedColor) {
    added = addedColor;
    changed = changedColor;
    removed = removedColor;
  }

  /**
   * Paints the marks of the visible rows.
   *
   * @param gc the paint event's GC
   * @param text the widget
   * @param projection the display text
   * @param x the mark column's left edge
   */
  void paint(GC gc, StyledText text, DisplayProjection projection, int x) {
    if (!shown || changes.kinds().length == 0 || added == null) {
      return;
    }
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    int half = WIDTH + 1;
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int line = projection.lineOfRow(row);
      int kind = changes.kind(line);
      if (kind == 0) {
        continue;
      }
      if ((kind & (LineChanges.ADDED | LineChanges.CHANGED)) != 0) {
        gc.setBackground((kind & LineChanges.CHANGED) != 0 ? changed : added);
        gc.fillRectangle(x, top, WIDTH, lineHeight);
      }
      gc.setBackground(removed);
      if ((kind & LineChanges.REMOVED_ABOVE) != 0 && projection.subRow(row) == 0) {
        gc.fillPolygon(new int[] {x - 1, top - half, x + WIDTH + 1, top, x - 1, top + half});
      }
      boolean lastRow = row + 1 >= rows || projection.lineOfRow(row + 1) != line;
      if ((kind & LineChanges.REMOVED_BELOW) != 0 && lastRow) {
        int bottom = top + lineHeight;
        gc.fillPolygon(
            new int[] {x - 1, bottom - half, x + WIDTH + 1, bottom, x - 1, bottom + half});
      }
    }
  }
}
