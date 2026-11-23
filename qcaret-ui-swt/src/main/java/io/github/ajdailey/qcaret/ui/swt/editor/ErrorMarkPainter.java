package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import java.util.Arrays;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Paints the format error mark: a wavy line, {@value #AMPLITUDE} px high, along the bottom of each
 * visible row part of the marked model range, in the palette's invalid token color. An empty range
 * at a line end is marked one average char wide. Nothing paints on a hidden line. Confined to the
 * UI thread.
 */
final class ErrorMarkPainter {
  /** The wave's height in pixels. */
  static final int AMPLITUDE = 2;

  private int start = -1;
  private int end = -1;
  private Color color;
  private int[] painted = new int[0];

  /**
   * Replaces the mark.
   *
   * @param from the model start, or -1 for none
   * @param to the model end
   */
  void set(int from, int to) {
    start = from;
    end = to;
  }

  /**
   * Returns the mark.
   *
   * @return {start, end}; {-1, -1} for none
   */
  int[] mark() {
    return start < 0 ? new int[] {-1, -1} : new int[] {start, end};
  }

  /**
   * Returns the segments the last paint drew.
   *
   * @return {x0, x1, y} per segment, y the wave's bottom
   */
  int[] painted() {
    return painted.clone();
  }

  void setColor(Color value) {
    color = value;
  }

  /**
   * Paints the mark.
   *
   * @param gc the paint event's GC
   * @param text the widget
   * @param projection the display text
   */
  void paint(GC gc, StyledText text, DisplayProjection projection) {
    painted = new int[0];
    if (start < 0 || color == null || end > projection.modelLength()) {
      return;
    }
    if (projection.hidden(projection.lineAt(start))) {
      return;
    }
    int from = projection.toDisplay(start);
    int to = Math.max(from, projection.toDisplay(end));
    Rectangle area = text.getClientArea();
    int lineHeight = text.getLineHeight();
    int[] out = new int[0];
    int row = projection.rowAt(from);
    while (true) {
      int rowStart = projection.rowStart(row);
      int rowEnd = rowStart + projection.rowText(row).length();
      int a = Math.max(from, rowStart);
      int b = Math.min(to, rowEnd);
      Point left = text.getLocationAtOffset(a);
      int x1 = text.getLocationAtOffset(b).x;
      if (b == a) {
        int average = (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth());
        x1 = left.x + Math.max(AMPLITUDE * 2, average);
      }
      int y = left.y + lineHeight - 1;
      if (y >= 0 && left.y <= area.height) {
        wave(gc, left.x, x1, y);
        out = Arrays.copyOf(out, out.length + 3);
        out[out.length - 3] = left.x;
        out[out.length - 2] = x1;
        out[out.length - 1] = y;
      }
      if (to <= rowEnd || row + 1 >= projection.rowCount()) {
        break;
      }
      row++;
    }
    painted = out;
  }

  private void wave(GC gc, int x0, int x1, int y) {
    int count = Math.max(2, (x1 - x0) / AMPLITUDE + 1);
    int[] points = new int[count * 2];
    for (int i = 0; i < count; i++) {
      points[i * 2] = Math.min(x1, x0 + i * AMPLITUDE);
      points[i * 2 + 1] = i % 2 == 0 ? y : y - AMPLITUDE;
    }
    // Unblended pixels keep the wave in the palette color.
    int antialias = gc.getAntialias();
    gc.setAntialias(SWT.OFF);
    gc.setForeground(color);
    gc.setLineWidth(1);
    gc.drawPolyline(points);
    gc.setAntialias(antialias);
  }
}
