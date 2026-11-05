package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
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
 * </ul>
 */
final class EditorGestures implements Listener {
  private final StyledText text;
  private final DisplayProjection projection;
  private final EditorInput input;
  private boolean columnMode;
  private boolean dragging;
  private boolean extending;
  private int anchorX;
  private int anchorY;

  EditorGestures(StyledText text, DisplayProjection projection, EditorInput input) {
    this.text = text;
    this.projection = projection;
    this.input = input;
    final Display display = text.getDisplay();
    display.addFilter(SWT.MouseDown, this);
    display.addFilter(SWT.MouseMove, this);
    display.addFilter(SWT.MouseUp, this);
    final Listener self = this;
    text.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.MouseDown, self);
            display.removeFilter(SWT.MouseMove, self);
            display.removeFilter(SWT.MouseUp, self);
          }
        });
  }

  void setColumnMode(boolean on) {
    columnMode = on;
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
            dragging = false;
          } else {
            drag(event.x, event.y);
          }
          consume(event);
        }
      }
      case SWT.MouseUp -> {
        if (dragging && event.button == 1) {
          dragging = false;
          consume(event);
        }
      }
      default -> {
        // Not filtered.
      }
    }
  }

  private void down(Event event) {
    if (event.button != 1 || event.count > 1) {
      return;
    }
    int mask = event.stateMask;
    boolean alt = (mask & SWT.ALT) != 0;
    boolean shift = (mask & SWT.SHIFT) != 0;
    boolean primary = (mask & SWT.MOD1) != 0;
    if (alt && shift && !primary) {
      extending = true;
    } else if (alt || (columnMode && (mask & (SWT.MOD1 | SWT.SHIFT)) == 0)) {
      extending = false;
      anchorX = event.x;
      anchorY = event.y;
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
    drag(event.x, event.y);
    consume(event);
  }

  private void drag(int x, int y) {
    if (extending) {
      input.extendBlockFromView(x, y);
    } else {
      input.blockFromView(anchorX, anchorY, x, y);
    }
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
