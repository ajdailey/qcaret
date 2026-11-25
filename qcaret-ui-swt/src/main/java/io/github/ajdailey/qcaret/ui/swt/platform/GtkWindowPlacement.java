package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Shell;

/**
 * Placement on GTK 3: the computed trim; the position is skipped under Wayland ({@link
 * PlatformTraits#GDK_BACKEND}).
 */
final class GtkWindowPlacement extends NativeWindowPlacement {
  @Override
  public void afterOpen(Shell shell, boolean maximized) {}

  @Override
  boolean positioned() {
    return !"wayland".equals(System.getProperty(PlatformTraits.GDK_BACKEND));
  }
}
