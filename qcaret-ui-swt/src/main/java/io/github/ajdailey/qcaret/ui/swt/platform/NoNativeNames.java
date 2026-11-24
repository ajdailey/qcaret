package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolItem;

/** Native names on GTK 4, win32 and cocoa: SWT asks the Java listeners, so nothing is set. */
final class NoNativeNames implements NativeNames {
  @Override
  public void name(ToolItem item, String name) {}

  @Override
  public void name(Control control, String name) {}
}
