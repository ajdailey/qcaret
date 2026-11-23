package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Which pane of one window had the focus last: an editor or a Markdown preview, from the controls
 * {@link #mark} names. Focus on any other control, such as the find bar or the toolbar, leaves the
 * record. A Display filter on {@code FocusIn} keeps it and is removed with the shell. Call on the
 * UI thread.
 */
final class PaneFocus {
  private static final String KEY = PaneFocus.class.getName();

  private final Shell shell;
  private final Runnable changed;
  private Control last;

  /**
   * Starts following the focus in a window.
   *
   * @param shell the window
   * @param changed runs when {@link #preview} changes
   */
  PaneFocus(final Shell shell, Runnable changed) {
    this.shell = shell;
    this.changed = changed;
    final Display display = shell.getDisplay();
    final Listener filter =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Control control) {
              focused(control);
            }
          }
        };
    display.addFilter(SWT.FocusIn, filter);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.FocusIn, filter);
          }
        });
  }

  /**
   * Names a control, or the parent of the controls taking the focus, as a pane.
   *
   * @param control the control
   * @param preview true for a Markdown preview, false for an editor
   */
  static void mark(Control control, boolean preview) {
    control.setData(KEY, preview);
  }

  private void focused(Control control) {
    if (control.isDisposed() || control.getShell() != shell) {
      return;
    }
    for (Control at = control; at != null; at = at.getParent()) {
      if (at.getData(KEY) instanceof Boolean) {
        boolean before = preview();
        last = at;
        if (preview() != before) {
          changed.run();
        }
        return;
      }
    }
  }

  /**
   * Reports whether a Markdown preview had the focus last of the window's panes and still shows.
   *
   * @return true for a preview
   */
  boolean preview() {
    return last != null
        && !last.isDisposed()
        && last.isVisible()
        && Boolean.TRUE.equals(last.getData(KEY));
  }

  /**
   * Returns the pane control that had the focus last.
   *
   * @return the control, or null before a pane had the focus or after it was disposed
   */
  Control last() {
    return last == null || last.isDisposed() ? null : last;
  }
}
