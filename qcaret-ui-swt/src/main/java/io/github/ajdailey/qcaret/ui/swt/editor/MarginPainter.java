package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;

/**
 * Paints the right margin line: a 1 px vertical line at a column's left edge, {@code round(column *
 * advance)} from the text's left edge ({@link ColumnMetrics}) less the horizontal scroll, so it
 * follows a scroll, a zoom and a font change and keeps its x under word wrap. A line left of the
 * text area or right of the client area is not painted. One line per paint, whatever the rows.
 * Confined to the UI thread.
 */
final class MarginPainter {
  private int column;
  private Color color;

  /**
   * Sets the column.
   *
   * @param value the column the line sits left of, or 0 for no line
   * @return true when it changed
   */
  boolean setColumn(int value) {
    if (value == column) {
      return false;
    }
    column = value;
    return true;
  }

  /**
   * Sets the line color.
   *
   * @param value the palette's right margin color
   */
  void setColor(Color value) {
    color = value;
  }

  /**
   * Returns the line's x in the widget.
   *
   * @param text the widget
   * @param metrics the font's column metrics
   * @return the x, or -1 without a line
   */
  int x(StyledText text, ColumnMetrics metrics) {
    if (column <= 0) {
      return -1;
    }
    return text.getLeftMargin() - text.getHorizontalPixel() + metrics.x(column);
  }

  /**
   * Paints the line over a damaged band.
   *
   * @param gc the paint event's GC
   * @param text the widget
   * @param metrics the font's column metrics
   * @param y the band's top
   * @param height the band's height
   */
  void paint(GC gc, StyledText text, ColumnMetrics metrics, int y, int height) {
    int x = x(text, metrics);
    if (x < text.getLeftMargin() || x >= text.getClientArea().width || color == null) {
      return;
    }
    gc.setForeground(color);
    gc.drawLine(x, y, x, y + height - 1);
  }
}
