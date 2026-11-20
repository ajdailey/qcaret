package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.widgets.Shell;

/**
 * A main window's View > Full Screen state, not saved. While {@link #on}, the window's tracked
 * bounds stay those from before. Confined to the UI thread.
 *
 * <p>Where {@link PlatformTraits#fullScreenSpace} holds (cocoa), the state is the shell's, which
 * the title bar button also changes. AppKit ignores a change requested during its transition
 * animation, so a request within {@link #SETTLE_MS} of the previous one waits until that time is
 * up, and a request the shell still does not show is made again, at most {@link #RETRIES} times.
 * Elsewhere the state is the last request, set before {@code setFullScreen} enters and cleared
 * after it leaves, so the size events SWT sends during the call already count as full screen.
 */
final class FullScreen {
  /** Time a cocoa transition is given before another request reaches the shell. */
  static final int SETTLE_MS = 1000;

  /** Further requests a cocoa change gets when the shell does not show it. */
  static final int RETRIES = 5;

  private final Shell shell;
  private final UiDispatcher ui;
  private final boolean space;
  private boolean requested;
  private boolean maximized;
  private Boolean wanted;
  private boolean changed;
  private long lastChange;
  private boolean scheduled;
  private int retries;

  FullScreen(Shell shell, UiDispatcher ui, PlatformTraits traits) {
    this.shell = shell;
    this.ui = ui;
    this.space = traits.fullScreenSpace();
  }

  /**
   * Reports whether the window fills the screen now.
   *
   * @return true while full screen
   */
  boolean on() {
    return space ? shell.getFullScreen() : requested;
  }

  /**
   * Reports the state View > Full Screen shows: a cocoa request still waiting for the shell, else
   * {@link #on}.
   *
   * @return true when full screen is on or requested
   */
  boolean shown() {
    return wanted != null ? wanted : on();
  }

  /**
   * Fills the screen with the window, or returns it to its bounds.
   *
   * @param value true to fill the screen
   */
  void set(boolean value) {
    if (shell.isDisposed() || value == shown()) {
      return;
    }
    if (value && !on()) {
      maximized = shell.getMaximized();
    }
    if (space) {
      wanted = value;
      retries = RETRIES;
      apply();
    } else if (value) {
      requested = true;
      shell.setFullScreen(true);
    } else {
      shell.setFullScreen(false);
      requested = false;
    }
  }

  /**
   * Passes the cocoa request to the shell once the last transition had its time, and checks it
   * again after each change.
   */
  private void apply() {
    if (shell.isDisposed() || wanted == null) {
      return;
    }
    long waited = changed ? (System.nanoTime() - lastChange) / 1_000_000L : SETTLE_MS;
    boolean settled = waited >= SETTLE_MS;
    if (shell.getFullScreen() == wanted) {
      if (settled) {
        wanted = null;
        return;
      }
    } else if (settled) {
      if (retries-- <= 0) {
        wanted = null;
        return;
      }
      shell.setFullScreen(wanted);
      changed = true;
      lastChange = System.nanoTime();
      waited = 0;
    }
    if (!scheduled) {
      scheduled = true;
      ui.timerExec(
          (int) Math.max(1, SETTLE_MS - waited),
          new Runnable() {
            @Override
            public void run() {
              scheduled = false;
              apply();
            }
          });
    }
  }

  /** Records the maximized state of a window that is not full screen. */
  void track() {
    if (!on()) {
      maximized = shell.getMaximized();
    }
  }

  /**
   * Reports whether the window is maximized, or was before full screen.
   *
   * @return the state
   */
  boolean maximized() {
    return on() ? maximized : shell.getMaximized();
  }
}
