package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The overview strip between an editor's text and its scroll bar: the whole document in its height,
 * a mark per pixel row holding a HyperSearch match, a find-bar match over it, and a tick at the
 * left edge for a bookmark. A line down its left edge separates it from the text, and a line across
 * it marks the first and the last visible line. A click or drag scrolls that line to the middle of
 * the view and runs {@code scrolled}. Painting reads the last {@link OverviewMarks} only; each
 * resize reports the height through {@link EditorInput#overviewResized}, 0 while hidden.
 */
final class OverviewStrip {
  /** Width in pixels. */
  static final int WIDTH = 14;

  /** Height of a mark in pixels. */
  static final int MARK_HEIGHT = 2;

  private final Canvas canvas;
  private final StyledText text;
  private final DisplayProjection projection;
  private final EditorInput input;
  private final Runnable scrolled;
  private OverviewMarks marks = OverviewMarks.EMPTY;
  private Color background;
  private Color frame;
  private Color find;
  private Color search;
  private Color bookmark;
  private boolean dragging;
  private int reported = -1;

  OverviewStrip(
      Composite parent,
      StyledText text,
      DisplayProjection projection,
      EditorInput input,
      Palette palette,
      Runnable scrolled) {
    this.text = text;
    this.projection = projection;
    this.input = input;
    this.scrolled = scrolled;
    canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND | SWT.NO_FOCUS);
    setPalette(palette);
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    canvas.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            report();
          }
        });
    canvas.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              dragging = true;
              scrollTo(event.y);
            }
          }
        });
    canvas.addListener(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (dragging) {
              scrollTo(event.y);
            }
          }
        });
    canvas.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragging = false;
          }
        });
  }

  Canvas control() {
    return canvas;
  }

  /** Reports the height, or 0 while hidden, when it changed. */
  void report() {
    int height = canvas.isDisposed() || !canvas.getVisible() ? 0 : canvas.getClientArea().height;
    if (height != reported) {
      reported = height;
      input.overviewResized(height);
    }
  }

  void setMarks(OverviewMarks value) {
    marks = value;
    if (!canvas.isDisposed()) {
      canvas.redraw();
    }
  }

  OverviewMarks marks() {
    return marks;
  }

  void setPalette(Palette palette) {
    background = color(palette.gutter());
    frame = color(palette.lineNumbers());
    find = color(palette.overviewFind());
    search = color(palette.overviewSearch());
    bookmark = color(palette.caret());
    if (!canvas.isDisposed()) {
      canvas.redraw();
    }
  }

  /** Returns the colors in paint order, for tests: background, frame, find, search, bookmark. */
  Color[] colors() {
    return new Color[] {background, frame, find, search, bookmark};
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Scrolls the line at a pixel row to the middle of the view. */
  private void scrollTo(int y) {
    int height = canvas.getClientArea().height;
    int lines = projection.lineCount();
    if (height <= 0 || lines <= 0 || text.isDisposed()) {
      return;
    }
    int line = OverviewMarks.lineAt(y, lines, height);
    int visible = Math.max(1, text.getClientArea().height / Math.max(1, text.getLineHeight()));
    text.setTopIndex(Math.max(0, projection.rowOfLine(line) - visible / 2));
    scrolled.run();
  }

  private void paint(GC gc) {
    Rectangle area = canvas.getClientArea();
    gc.setBackground(background);
    gc.fillRectangle(area);
    byte[] rows = marks.rows();
    if (rows.length > 0 && area.height > 0) {
      for (int at = 0; at < rows.length; at++) {
        int kinds = rows[at];
        if (kinds == 0) {
          continue;
        }
        int y = (int) ((long) at * area.height / rows.length);
        if ((kinds & OverviewMarks.SEARCH) != 0) {
          gc.setBackground(search);
          gc.fillRectangle(3, y, area.width - 4, MARK_HEIGHT);
        }
        if ((kinds & OverviewMarks.FIND) != 0) {
          gc.setBackground(find);
          gc.fillRectangle(3, y, area.width - 4, MARK_HEIGHT);
        }
        if ((kinds & OverviewMarks.BOOKMARK) != 0) {
          gc.setBackground(bookmark);
          gc.fillRectangle(1, y, 2, MARK_HEIGHT + 1);
        }
      }
    }
    gc.setForeground(frame);
    gc.drawLine(0, 0, 0, area.height - 1);
    int lines = projection.lineCount();
    int rowCount = projection.rowCount();
    if (lines > 0 && rowCount > 0 && area.height > 0 && !text.isDisposed()) {
      int topRow = Math.min(text.getTopIndex(), rowCount - 1);
      int bottomRow = Math.min(rowCount - 1, text.getLineIndex(text.getClientArea().height - 1));
      int top = OverviewMarks.rowOf(projection.lineOfRow(topRow), lines, area.height);
      int bottom = OverviewMarks.rowOf(projection.lineOfRow(bottomRow), lines, area.height);
      gc.drawLine(1, top, area.width - 1, top);
      gc.drawLine(1, bottom, area.width - 1, bottom);
    }
  }
}
