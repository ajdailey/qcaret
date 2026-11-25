package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Placement on GTK 4: the computed trim with no position, which SWT cannot set there, and a
 * maximize repeated after {@code open}, since GTK 4 drops one requested before the shell maps.
 */
final class Gtk4WindowPlacement extends NativeWindowPlacement {
  @Override
  public void afterOpen(Shell shell, boolean maximized) {
    if (maximized && !shell.getMaximized()) {
      shell.setMaximized(true);
    }
  }

  @Override
  boolean positioned() {
    return false;
  }
}
