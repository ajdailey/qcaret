package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.widgets.Shell;

/**
 * A main window's View > Full Screen state, not saved. Where {@link PlatformTraits#fullScreenSpace}
 * holds (cocoa), the state is the shell's, which the title bar button also changes; elsewhere it is
 * the last request, set before {@code setFullScreen} enters and cleared after it leaves, so the
 * size events SWT sends during the call already count as full screen. While {@link #on}, the
 * window's tracked bounds stay those from before. Confined to the UI thread.
 */
final class FullScreen {
  private final Shell shell;
  private final boolean space;
  private boolean requested;
  private boolean maximized;

  FullScreen(Shell shell, PlatformTraits traits) {
    this.shell = shell;
    this.space = traits.fullScreenSpace();
  }

  /**
   * Reports whether the window fills the screen.
   *
   * @return true while full screen
   */
  boolean on() {
    return space ? shell.getFullScreen() : requested;
  }

  /**
   * Fills the screen with the window, or returns it to its bounds.
   *
   * @param value true to fill the screen
   */
  void set(boolean value) {
    if (shell.isDisposed() || value == on()) {
      return;
    }
    if (value) {
      maximized = shell.getMaximized();
    }
    if (space) {
      shell.setFullScreen(value);
    } else if (value) {
      requested = true;
      shell.setFullScreen(true);
    } else {
      shell.setFullScreen(false);
      requested = false;
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
