package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/** The maximized state every {@link FullScreen} keeps across full screen. */
abstract class TrackedFullScreen implements FullScreen {
  /** The window. */
  final Shell shell;

  private boolean maximized;

  TrackedFullScreen(Shell shell) {
    this.shell = shell;
  }

  @Override
  public final void set(boolean value) {
    if (shell.isDisposed() || value == shown()) {
      return;
    }
    if (value && !on()) {
      maximized = shell.getMaximized();
    }
    change(value);
  }

  /**
   * Requests the change {@link #set} asks for.
   *
   * @param value true to fill the screen
   */
  abstract void change(boolean value);

  @Override
  public final void track() {
    if (!on()) {
      maximized = shell.getMaximized();
    }
  }

  @Override
  public final boolean maximized() {
    return on() ? maximized : shell.getMaximized();
  }
}
