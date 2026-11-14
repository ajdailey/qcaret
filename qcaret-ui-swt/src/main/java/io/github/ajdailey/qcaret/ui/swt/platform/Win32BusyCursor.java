package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;

/** The busy cursor on win32: {@link SWT#CURSOR_APPSTARTING}, the native arrow with a spinner. */
final class Win32BusyCursor implements BusyCursor {
  @Override
  public int style() {
    return SWT.CURSOR_APPSTARTING;
  }
}
