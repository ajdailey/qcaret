package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;

/**
 * The busy cursor on GTK 3 and GTK 4: {@link SWT#CURSOR_APPSTARTING}, the theme's arrow with a
 * watch.
 */
final class GtkBusyCursor implements BusyCursor {
  @Override
  public int style() {
    return SWT.CURSOR_APPSTARTING;
  }
}
