package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The editor's own mouse gestures, taken from a Display filter before the widget sees them, since
 * the widget's Alt+click adds a native selection and a typed listener runs after the widget's.
 *
 * <ul>
 *   <li>Alt+drag, and Ctrl+Alt+drag where the window manager takes Alt+drag (Linux), select a
 *       block; Alt+Shift+click and drag extend the block from its anchor or the primary caret. In
 *       column selection mode a plain drag selects a block.
 *   <li>Mod+click adds a caret, or removes the one clicked when several exist.
 *   <li>A click in the gutter's fold marker column toggles the fold region starting on that row's
 *       line; the widget's caret stays.
 *   <li>A block drag held above, below, left or right of the text area extends the block toward the
 *       pointer every {@link #AUTOSCROLL_MILLIS} on a {@link UiDispatcher} timer, and the
 *       controller scrolls to the block's caret. Release, focus loss and dispose cancel the timer.
 * </ul>
 */
final class EditorGestures implements Listener {
  /** Interval between extensions while a block drag holds the pointer outside the text area. */
  static final int AUTOSCROLL_MILLIS = 50;

  private final StyledText text;
  private final DisplayProjection projection;
  private final EditorInput input;
  private final UiDispatcher timers;
  private final Runnable autoscroll =
      new Runnable() {
        @Override
        public void run() {
          autoscrollStep();
        }
      };
  private boolean columnMode;
  private boolean dragging;
  private boolean autoscrolling;
  private int dragX;
  private int dragY;
  private int foldLeft;
  private int foldRight;

  EditorGestures(
      StyledText text, DisplayProjection projection, EditorInput input, UiDispatcher timers) {
    this.text = text;
    this.projection = projection;
    this.input = input;
    this.timers = timers;
    final Display display = text.getDisplay();
    display.addFilter(SWT.MouseDown, this);
    display.addFilter(SWT.MouseMove, this);
    display.addFilter(SWT.MouseUp, this);
    final Listener self = this;
    text.addListener(
        SWT.FocusOut,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            endDrag();
          }
        });
    text.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            endDrag();
            display.removeFilter(SWT.MouseDown, self);
            display.removeFilter(SWT.MouseMove, self);
            display.removeFilter(SWT.MouseUp, self);
          }
        });
  }

  void setColumnMode(boolean on) {
    columnMode = on;
  }

  /** Sets the fold marker column's horizontal extent, in pixels from the widget's left edge. */
  void setFoldColumn(int left, int right) {
    foldLeft = left;
    foldRight = right;
  }

  @Override
  public void handleEvent(Event event) {
    if (event.widget != text || text.isDisposed()) {
      return;
    }
    switch (event.type) {
      case SWT.MouseDown -> down(event);
      case SWT.MouseMove -> {
        if (dragging) {
          if ((event.stateMask & SWT.BUTTON1) == 0) {
            endDrag();
          } else {
            drag(event.x, event.y);
          }
          consume(event);
        }
      }
      case SWT.MouseUp -> {
        if (dragging && event.button == 1) {
          endDrag();
          consume(event);
        }
      }
      default -> {
        // Not filtered.
      }
    }
  }

  private void down(Event event) {
    if (event.button != 1) {
      return;
    }
    if (event.x >= foldLeft && event.x < foldRight) {
      if (event.count == 1 && (event.stateMask & SWT.MODIFIER_MASK) == 0) {
        input.toggleFoldFromView(text.getLineIndex(event.y));
      }
      consume(event);
      return;
    }
    if (event.count > 1) {
      return;
    }
    int mask = event.stateMask;
    boolean alt = (mask & SWT.ALT) != 0;
    boolean shift = (mask & SWT.SHIFT) != 0;
    boolean primary = (mask & SWT.MOD1) != 0;
    if (alt && shift && !primary) {
      input.extendBlockFromView(event.x, event.y);
    } else if (alt || (columnMode && (mask & (SWT.MOD1 | SWT.SHIFT)) == 0)) {
      // The anchor becomes a line and column here: a drag step can scroll the widget.
      input.blockFromView(event.x, event.y, event.x, event.y);
    } else if (primary && !shift) {
      input.toggleCaretFromView(offsetAt(event.x, event.y));
      text.setFocus();
      consume(event);
      return;
    } else {
      return;
    }
    dragging = true;
    text.setFocus();
    consume(event);
  }

  private void drag(int x, int y) {
    dragX = x;
    dragY = y;
    if (autoscrolling) {
      input.extendBlockFromView(x, y);
    } else {
      autoscrollStep();
    }
  }

  /** Extends the block to the pointer and rearms the timer while the pointer is outside. */
  private void autoscrollStep() {
    autoscrolling = false;
    if (!dragging || text.isDisposed()) {
      return;
    }
    input.extendBlockFromView(dragX, dragY);
    Rectangle area = text.getClientArea();
    if (dragY < 0
        || dragY >= area.height
        || dragX < text.getLeftMargin()
        || dragX >= area.width - text.getRightMargin()) {
      autoscrolling = true;
      timers.timerExec(AUTOSCROLL_MILLIS, autoscroll);
    }
  }

  private void endDrag() {
    dragging = false;
    if (autoscrolling) {
      autoscrolling = false;
      timers.cancelTimer(autoscroll);
    }
  }

  /**
   * Reports whether the drag timer is armed.
   *
   * @return true while a block drag holds the pointer outside the text area
   */
  boolean autoscrolling() {
    return autoscrolling;
  }

  /** Returns the display offset nearest a point, the row's end past it. */
  private int offsetAt(int x, int y) {
    int at = text.getOffsetAtPoint(new Point(x, y));
    if (at >= 0) {
      return at;
    }
    int row = Math.max(0, Math.min(projection.rowCount() - 1, text.getLineIndex(y)));
    return projection.rowStart(row) + projection.rowText(row).length();
  }

  private static void consume(Event event) {
    event.type = SWT.None;
    event.doit = false;
  }
}
