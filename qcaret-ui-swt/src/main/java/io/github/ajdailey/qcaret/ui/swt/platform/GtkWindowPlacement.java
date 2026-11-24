package io.github.ajdailey.qcaret.ui.swt.platform;

/**
 * Placement on GTK: the computed trim; the position is skipped on GTK 4, which SWT cannot move, and
 * on GTK 3 under Wayland ({@link PlatformTraits#GDK_BACKEND}).
 */
final class GtkWindowPlacement extends NativeWindowPlacement {
  private final boolean gtk4;

  GtkWindowPlacement(boolean gtk4) {
    this.gtk4 = gtk4;
  }

  @Override
  boolean positioned() {
    return !gtk4 && !"wayland".equals(System.getProperty(PlatformTraits.GDK_BACKEND));
  }
}
