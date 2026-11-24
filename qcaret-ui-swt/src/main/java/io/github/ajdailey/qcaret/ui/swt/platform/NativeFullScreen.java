package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Full screen on win32 and GTK: the window keeps its menu bar and only {@code setFullScreen}
 * changes the state. The state is the last request, set before {@code setFullScreen} enters and
 * cleared after it leaves, so the size events SWT sends during the call already count as full
 * screen.
 */
final class NativeFullScreen extends TrackedFullScreen {
  private boolean requested;

  NativeFullScreen(Shell shell) {
    super(shell);
  }

  @Override
  public boolean on() {
    return requested;
  }

  @Override
  public boolean shown() {
    return requested;
  }

  @Override
  void change(boolean value) {
    if (value) {
      requested = true;
      shell.setFullScreen(true);
    } else {
      shell.setFullScreen(false);
      requested = false;
    }
  }
}
