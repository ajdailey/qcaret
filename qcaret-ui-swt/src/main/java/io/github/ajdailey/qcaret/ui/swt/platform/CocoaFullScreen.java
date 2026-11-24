package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Shell;

/**
 * Full screen on cocoa, which moves the window to a space of its own: the system animates the
 * change after {@code Shell.setFullScreen} returns, the title bar button also enters and leaves it,
 * and the system menu bar shows when the pointer reaches the top edge. The state is the shell's.
 * AppKit ignores a change requested during its transition animation, so a request within {@link
 * #SETTLE_MS} of the previous one waits until that time is up, and a request the shell still does
 * not show is made again, at most {@link #RETRIES} times.
 */
final class CocoaFullScreen extends TrackedFullScreen {
  /** Time a transition is given before another request reaches the shell. */
  static final int SETTLE_MS = 1000;

  /** Further requests a change gets when the shell does not show it. */
  static final int RETRIES = 5;

  private final UiDispatcher ui;
  private Boolean wanted;
  private boolean changed;
  private long lastChange;
  private boolean scheduled;
  private int retries;

  CocoaFullScreen(Shell shell, UiDispatcher ui) {
    super(shell);
    this.ui = ui;
  }

  @Override
  public boolean on() {
    return shell.getFullScreen();
  }

  @Override
  public boolean shown() {
    return wanted != null ? wanted : on();
  }

  @Override
  void change(boolean value) {
    wanted = value;
    retries = RETRIES;
    apply();
  }

  /**
   * Passes the request to the shell once the last transition had its time, and checks it again
   * after each change.
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
}
