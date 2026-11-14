package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;

/**
 * The busy cursor on cocoa: {@link SWT#CURSOR_WAIT}, which SWT 3.135 shows as AppKit's arrow with a
 * spinner. cocoa SWT shows {@code CURSOR_APPSTARTING} as a plain arrow.
 */
final class CocoaBusyCursor implements BusyCursor {
  @Override
  public int style() {
    return SWT.CURSOR_WAIT;
  }
}
