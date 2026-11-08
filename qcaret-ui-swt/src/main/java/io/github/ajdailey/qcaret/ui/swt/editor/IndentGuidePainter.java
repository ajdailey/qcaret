package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import java.util.Arrays;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Paints the indent guides: a 1 px vertical line, one row high, at each indent level of a line's
 * leading spaces and tabs, at columns 0, w, 2w and on below the indent's width, where w is the
 * widget's tab width and a tab runs to the next multiple of w. A guide sits at the left edge of the
 * char starting its column, at the widget's measured position, so it follows a scroll, a zoom and a
 * font change. Only the first row of a line has guides; a wrapped line's continuation rows draw
 * none. A row of blanks alone takes the guides of the rows around it: the levels below both the row
 * above's and the row below's indents, at the row above's positions, each found within {@value
 * #SCAN_ROWS} rows. Only the first {@value #MAX_INDENT_CHARS} chars of a row are read, so work per
 * paint is bounded by the visible rows, that count and the scan. Confined to the UI thread.
 */
final class IndentGuidePainter {
  /** Rows searched above and below a blank row for its neighbors. */
  static final int SCAN_ROWS = 100;

  /** Leading chars of a row read for its indent. */
  static final int MAX_INDENT_CHARS = 1000;

  private boolean shown;
  private Color color;

  /**
   * Shows or hides the guides.
   *
   * @param value true to show them
   * @return true when it changed
   */
  boolean setShown(boolean value) {
    if (value == shown) {
      return false;
    }
    shown = value;
    return true;
  }

  /**
   * Reports whether the guides show.
   *
   * @return true while shown
   */
  boolean shown() {
    return shown;
  }

  /**
   * Sets the guide color.
   *
   * @param value the palette's indent guide color
   */
  void setColor(Color value) {
    color = value;
  }

  /**
   * Paints the guides of the visible rows.
   *
   * @param gc the paint event's GC
   * @param text the widget
   * @param projection the display text
   */
  void paint(GC gc, StyledText text, DisplayProjection projection) {
    if (!shown || color == null) {
      return;
    }
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    gc.setBackground(color);
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int[] xs = guides(text, projection, row, area.width);
      for (int x : xs) {
        if (x >= text.getLeftMargin()) {
          gc.fillRectangle(x, top, 1, lineHeight);
        }
      }
    }
  }

  /**
   * Returns a row's guide positions.
   *
   * @param text the widget
   * @param projection the display text
   * @param row a display row
   * @param width the client area's width; guides right of it are left out
   * @return x per guide, left to right; empty for a continuation row or no indent
   */
  static int[] guides(StyledText text, DisplayProjection projection, int row, int width) {
    if (projection.subRow(row) != 0) {
      return new int[0];
    }
    int tab = Math.max(1, text.getTabs());
    String rowText = projection.rowText(row);
    if (!blank(rowText)) {
      return positions(text, projection.rowStart(row), rowText, tab, Integer.MAX_VALUE, width);
    }
    int above = neighbor(projection, row, -1);
    int below = neighbor(projection, row, 1);
    if (above < 0 || below < 0) {
      return new int[0];
    }
    String aboveText = projection.rowText(above);
    int limit = Math.min(indent(aboveText, tab), indent(projection.rowText(below), tab));
    return positions(text, projection.rowStart(above), aboveText, tab, limit, width);
  }

  /** Returns the nearest first row of a line that is not blank, or -1 within the scan. */
  private static int neighbor(DisplayProjection projection, int row, int step) {
    int rows = projection.rowCount();
    int at = row + step;
    for (int i = 0; i < SCAN_ROWS && at >= 0 && at < rows; i++, at += step) {
      if (projection.subRow(at) == 0 && !blank(projection.rowText(at))) {
        return at;
      }
    }
    return -1;
  }

  /**
   * Returns the x of each indent level below {@code limit} columns in a row's leading blanks, up to
   * the first one right of {@code width}.
   */
  private static int[] positions(
      StyledText text, int rowStart, String rowText, int tab, int limit, int width) {
    int[] xs = new int[8];
    int count = 0;
    int column = 0;
    int length = Math.min(rowText.length(), MAX_INDENT_CHARS);
    for (int i = 0; i < length && column < limit; i++) {
      char c = rowText.charAt(i);
      if (c != ' ' && c != '\t') {
        break;
      }
      if (column % tab == 0) {
        int x = text.getLocationAtOffset(rowStart + i).x;
        if (x >= width) {
          break;
        }
        if (count == xs.length) {
          xs = Arrays.copyOf(xs, count * 2);
        }
        xs[count++] = x;
      }
      column = c == '\t' ? (column / tab + 1) * tab : column + 1;
    }
    return Arrays.copyOf(xs, count);
  }

  /** Returns the columns of a row's leading blanks. */
  private static int indent(String rowText, int tab) {
    int column = 0;
    int length = Math.min(rowText.length(), MAX_INDENT_CHARS);
    for (int i = 0; i < length; i++) {
      char c = rowText.charAt(i);
      if (c == ' ') {
        column++;
      } else if (c == '\t') {
        column = (column / tab + 1) * tab;
      } else {
        break;
      }
    }
    return column;
  }

  /** Reports a row of blanks alone; a row longer than {@value #MAX_INDENT_CHARS} chars is not. */
  private static boolean blank(String rowText) {
    if (rowText.length() > MAX_INDENT_CHARS) {
      return false;
    }
    for (int i = 0; i < rowText.length(); i++) {
      char c = rowText.charAt(i);
      if (c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }
}
