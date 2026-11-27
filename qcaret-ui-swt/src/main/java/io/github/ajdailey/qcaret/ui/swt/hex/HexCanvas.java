package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexScroll;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Slider;

/**
 * The rows of a hex view: a double-buffered {@link Canvas} painted by {@link HexPainter}, with a
 * {@link Slider} as its vertical scroll bar, input through {@link HexGestures}. The grid is
 * measured from the font: the advance is the width of {@link ColumnMetrics#sample} over its length,
 * kept fractional, and each cell sits at {@code round(column * advance)}. A resize, a font change
 * or {@code SWT.ZoomChanged} measures again and reports the visible rows. The font belongs to the
 * caller, which replaces it through {@link #setFont} before disposing the old one. Call on the UI
 * thread.
 */
final class HexCanvas {
  private final Composite frame;
  private final Canvas canvas;
  private final Slider slider;
  private final HexInput input;
  private final HexPainter painter;
  private final HexGestures gestures;
  private Font font;
  private double advance = 1;
  private int lineHeight = 1;
  private HexRows rows = new HexRows(0, HexRows.WIDTHS.get(1));
  private HexGrid grid;
  private HexScroll scroll = new HexScroll(0, 1, 1, 0);
  private HexSelection selection = new HexSelection(0, 0, false);
  private boolean dimmed;
  private boolean focused;
  private int shownRows = -1;

  /**
   * Creates the rows in a parent.
   *
   * @param parent the parent
   * @param input the view's controller
   * @param palette the colors
   * @param font the editor font
   * @param keymap the key bindings
   * @param timers runs the drag autoscroll
   * @param commands the find bar and window commands
   * @param windowKeys true in a Hex Viewer window, whose keys the main menu does not run
   */
  HexCanvas(
      Composite parent,
      HexInput input,
      Palette palette,
      Font font,
      Keymap keymap,
      UiDispatcher timers,
      HexGestures.Commands commands,
      boolean windowKeys) {
    this.input = input;
    this.font = font;
    this.painter = new HexPainter(palette);
    frame = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    frame.setLayout(layout);
    canvas = new Canvas(frame, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
    canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    canvas.setFont(font);
    AccessibleName.attach(canvas, UiText.ACCESSIBLE_HEX_VIEW.text());
    slider = new Slider(frame, SWT.VERTICAL);
    slider.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
    slider.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.scrolled(slider.getSelection());
          }
        });
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    Listener remeasure =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            measure();
          }
        };
    canvas.addListener(SWT.Resize, remeasure);
    canvas.addListener(SWT.ZoomChanged, remeasure);
    gestures = new HexGestures(this, canvas, input, keymap, timers, commands, windowKeys);
    measure();
  }

  /** Returns the frame holding the rows and the scroll bar. */
  Composite control() {
    return frame;
  }

  /** Returns the canvas the rows paint on. */
  Canvas canvas() {
    return canvas;
  }

  /** Returns the scroll bar. */
  Slider slider() {
    return slider;
  }

  /** Returns the context menu, or null before it first showed. */
  Menu menu() {
    return gestures.menu();
  }

  /** Runs a command as its key would; see {@link HexGestures#command}. */
  boolean command(CommandId command) {
    return gestures.command(command);
  }

  /** Runs a window key pressed in the find field; see {@link HexGestures#findFieldKey}. */
  boolean findFieldKey(Event event) {
    return gestures.findFieldKey(event);
  }

  Color background() {
    return painter.background();
  }

  HexGrid grid() {
    return grid;
  }

  long topRow() {
    return scroll.topRow();
  }

  HexSelection selection() {
    return selection;
  }

  /**
   * Returns the width a full row and the scroll bar need.
   *
   * @return pixels
   */
  int preferredWidth() {
    return grid.rowWidth() + slider.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
  }

  /**
   * Returns the x of a column's left edge.
   *
   * @param column a column of the layout's {@link HexRows}
   * @return pixels from the canvas's left
   */
  int columnX(int column) {
    return grid.x(column);
  }

  /**
   * Returns the layout shown.
   *
   * @return the layout
   */
  HexRows rows() {
    return rows;
  }

  /**
   * Returns the rows that fit the canvas.
   *
   * @return at least 1
   */
  int visibleRows() {
    return grid.visibleRows(canvas.getClientArea().height);
  }

  /** Shows a layout and measures the grid for it. */
  void showLayout(HexRows value) {
    rows = value;
    grid = new HexGrid(rows, advance, lineHeight);
    canvas.redraw();
  }

  /** Shows the scroll state. */
  void showScroll(HexScroll value) {
    boolean moved = value.topRow() != scroll.topRow();
    scroll = value;
    int thumb = Math.max(1, value.thumb());
    slider.setValues(value.selection(), 0, Math.max(1, value.maximum()), thumb, 1, thumb);
    if (moved) {
      canvas.redraw();
    }
  }

  /** Shows the caret and selection. */
  void showSelection(HexSelection value) {
    selection = value;
    canvas.redraw();
  }

  /** Dims or undims the rows. */
  void setDimmed(boolean value) {
    if (dimmed != value) {
      dimmed = value;
      canvas.redraw();
    }
  }

  /** Repaints the rows. */
  void redraw() {
    canvas.redraw();
  }

  /** Paints with a palette's colors. */
  void setPalette(Palette palette) {
    painter.setPalette(palette);
    canvas.redraw();
  }

  /**
   * Paints in another font; the caller disposes the old one after this returns.
   *
   * @param next the font
   */
  void setFont(Font next) {
    font = next;
    canvas.setFont(next);
    measure();
    canvas.redraw();
  }

  /** Takes changed key bindings. */
  void setKeymap(Keymap next) {
    gestures.setKeymap(next);
  }

  /** Moves the keyboard focus to the rows. */
  void focus() {
    canvas.setFocus();
  }

  void focusChanged(boolean value) {
    focused = value;
    canvas.redraw();
  }

  /** Measures the font's grid and reports the rows that fit. */
  private void measure() {
    if (canvas.isDisposed()) {
      return;
    }
    GC gc = new GC(canvas);
    try {
      gc.setFont(font);
      advance = ColumnMetrics.fromSample(gc.textExtent(ColumnMetrics.sample()).x).advance();
      lineHeight = gc.getFontMetrics().getHeight();
    } finally {
      gc.dispose();
    }
    grid = new HexGrid(rows, advance, lineHeight);
    int fit = visibleRows();
    if (fit != shownRows) {
      shownRows = fit;
      input.resized(fit);
    }
  }

  private void paint(GC gc) {
    Rectangle area = canvas.getClientArea();
    gc.setFont(font);
    painter.paint(
        gc,
        area.width,
        area.height,
        new HexPainter.View(grid, scroll.topRow(), visibleRows() + 1, selection, focused, dimmed),
        input);
  }
}
