package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.BusyCounter;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.BusyCursor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * A main window's busy cursor, {@link BusyCursor#PLATFORM}, driven by its {@link BusyCounter}.
 * While busy it shows over the shell, over every editor ({@link EditorPane#setWindowBusy}) and over
 * every shell the window owns at any depth, modal or modeless, with each {@link StyledText} in it,
 * which otherwise shows its own I-beam over the shell's cursor. An owned shell that shows while the
 * window is busy, and an editor passed to {@link #paneAdded}, start busy. Idle gives each its own
 * cursor back. Confined to the UI thread.
 */
final class WindowBusyCursor implements BusyCounter.Indicator {
  private final Shell shell;
  private final TabStrip tabs;
  private final Listener shown =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (event.widget instanceof Shell owned && owned != shell && owns(owned)) {
            show(owned, BusyCursor.PLATFORM.cursor(shell.getDisplay()));
          }
        }
      };
  private boolean busy;

  /**
   * Creates the cursor, idle.
   *
   * @param shell the main window's shell
   * @param tabs the window's editors
   */
  WindowBusyCursor(Shell shell, TabStrip tabs) {
    this.shell = shell;
    this.tabs = tabs;
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            watch(false);
          }
        });
  }

  @Override
  public void busyChanged(boolean on) {
    if (shell.isDisposed()) {
      return;
    }
    busy = on;
    Cursor cursor = on ? BusyCursor.PLATFORM.cursor(shell.getDisplay()) : null;
    shell.setCursor(cursor);
    for (EditorPane pane : tabs.allPanes()) {
      pane.setWindowBusy(on);
    }
    for (Shell owned : shell.getShells()) {
      show(owned, cursor);
    }
    watch(on);
  }

  /**
   * Shows the busy cursor over a new editor while the window is busy.
   *
   * @param pane the editor
   */
  void paneAdded(EditorPane pane) {
    if (busy) {
      pane.setWindowBusy(true);
    }
  }

  /** Reports whether the window's shell owns the shell, at any depth. */
  private boolean owns(Shell owned) {
    Composite parent = owned.getParent();
    while (parent != null) {
      if (parent == shell) {
        return true;
      }
      parent = parent.getParent();
    }
    return false;
  }

  /** Sets the cursor on a shell and on each {@code StyledText} in it; null restores their own. */
  private static void show(Control control, Cursor cursor) {
    if (control.isDisposed()) {
      return;
    }
    if (control instanceof Shell || control instanceof StyledText) {
      control.setCursor(cursor);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        show(child, cursor);
      }
    }
  }

  /** Adds the Show filter while busy and removes it when idle or disposed. */
  private void watch(boolean on) {
    Display display = shell.getDisplay();
    if (display.isDisposed()) {
      return;
    }
    if (on) {
      display.addFilter(SWT.Show, shown);
    } else {
      display.removeFilter(SWT.Show, shown);
    }
  }
}
