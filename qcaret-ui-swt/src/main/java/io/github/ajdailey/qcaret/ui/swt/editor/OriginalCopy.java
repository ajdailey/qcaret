package io.github.ajdailey.qcaret.ui.swt.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The widget's own clipboard writes for a content whose {@code getLine} gives {@link
 * io.github.ajdailey.qcaret.core.text.ControlChars#shown} lines. {@code StyledText} builds plain
 * text, RTF and HTML from {@code getLine}, so the content gives original lines while {@link
 * #copying} holds, and {@link #copy} holds it for the length of one widget copy.
 */
final class OriginalCopy {
  private boolean copying;
  private Point primary;

  /**
   * Reports whether a widget copy is running.
   *
   * @return true while {@link #copy} runs
   */
  boolean copying() {
    return copying;
  }

  /**
   * Copies the widget's selection with its original chars.
   *
   * @param widget the widget
   * @param clipboard {@code DND.CLIPBOARD} or {@code DND.SELECTION_CLIPBOARD}
   */
  void copy(StyledText widget, int clipboard) {
    copying = true;
    try {
      widget.copy(clipboard);
    } finally {
      copying = false;
    }
  }

  /**
   * Runs a read-only widget's copy and cut keys through {@link #copy}.
   *
   * @param widget the widget
   */
  void routeKeys(final StyledText widget) {
    widget.addVerifyKeyListener(
        new VerifyKeyListener() {
          @Override
          public void verifyKey(VerifyEvent event) {
            int key = event.keyCode != 0 ? event.keyCode : event.character;
            int action = widget.getKeyBinding(key | event.stateMask);
            if (action == ST.COPY || action == ST.CUT) {
              event.doit = false;
              copy(widget, DND.CLIPBOARD);
            }
          }
        });
  }

  /**
   * Writes the selection clipboard (GTK only) again after a mouse release or key that changed the
   * selection: {@code StyledText}'s own listeners, added when the widget was created and so run
   * first, have just written it with placeholders.
   *
   * @param widget the widget
   */
  void routeSelectionClipboard(final StyledText widget) {
    Listener rewrite =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            Point range = widget.getSelection();
            if (range.x == range.y) {
              primary = null;
            } else if (event.type == SWT.MouseUp ? event.button == 1 : !range.equals(primary)) {
              primary = range;
              copy(widget, DND.SELECTION_CLIPBOARD);
            }
          }
        };
    widget.addListener(SWT.MouseUp, rewrite);
    widget.addListener(SWT.KeyUp, rewrite);
  }
}
