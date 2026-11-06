package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.document.Columns;
import java.util.function.Supplier;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;

/**
 * The {@link ViewLayout} of one editor's {@link StyledText}. A row of printable ASCII without tabs
 * maps x to a column by the font's fractional advance from the row's left edge (QLogg UI-1), as
 * long as the row's measured width agrees with that advance; a family's bold or italic face can be
 * wider than its regular face. Any other row maps by the widget's measured glyph positions. Past a
 * row's end, columns continue at the fractional advance into virtual space.
 */
final class EditorLayout implements ViewLayout {
  private final StyledText text;
  private final DisplayProjection projection;
  private final Supplier<ColumnMetrics> metrics;

  EditorLayout(StyledText text, DisplayProjection projection, Supplier<ColumnMetrics> metrics) {
    this.text = text;
    this.projection = projection;
    this.metrics = metrics;
  }

  @Override
  public int pageRows() {
    int height = text.getClientArea().height;
    return Math.max(1, height / Math.max(1, text.getLineHeight()) - 1);
  }

  @Override
  public int rowAt(int y) {
    int top = text.getLinePixel(0);
    if (y < top) {
      return 0;
    }
    int row = text.getLineIndex(y);
    return Math.max(0, Math.min(projection.rowCount() - 1, row));
  }

  @Override
  public int columnAt(int row, int x) {
    int rowStart = projection.rowStart(row);
    String rowText = projection.rowText(row);
    int rowEnd = rowStart + rowText.length();
    int startX = text.getLocationAtOffset(rowStart).x;
    int endX = text.getLocationAtOffset(rowEnd).x;
    double advance = metrics.get().advance();
    int index;
    if (x >= endX) {
      int past = (int) Math.round((x - endX) / advance);
      return lineColumn(rowEnd) + Math.max(0, past);
    } else if (gridRow(rowText) && onGrid(endX - startX, rowText.length(), advance)) {
      index = (int) Math.round((x - startX) / advance);
    } else {
      int y = text.getLinePixel(row) + Math.max(0, text.getLineHeight() / 2);
      int at = text.getOffsetAtPoint(new Point(Math.max(x, startX), y));
      index = at < 0 ? 0 : at - rowStart;
    }
    return lineColumn(rowStart + Math.max(0, Math.min(index, rowText.length())));
  }

  /** Reports whether a row's width stays within half a column of its chars at the advance. */
  static boolean onGrid(int width, int chars, double advance) {
    return Math.abs(width - chars * advance) < advance / 2;
  }

  /** Returns the visual column of a display offset in its model line. */
  int lineColumn(int displayOffset) {
    int model = projection.toModel(displayOffset);
    int line = projection.lineAt(model);
    String before = projection.modelText(projection.lineStart(line), model);
    return Columns.column(before, before.length(), projection.tabWidth());
  }

  /** Reports whether a row is printable ASCII without tabs, so columns sit on the font's grid. */
  static boolean gridRow(String row) {
    for (int i = 0; i < row.length(); i++) {
      char c = row.charAt(i);
      if (c < ' ' || c > '~') {
        return false;
      }
    }
    return true;
  }
}
