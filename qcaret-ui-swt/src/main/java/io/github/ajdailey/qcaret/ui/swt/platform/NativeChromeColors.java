package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Control;

/**
 * Chrome colors on GTK and cocoa: the native theme draws the main window's surfaces, fields and
 * trees dark and light, and {@code ToolIcons} inks from them, so nothing is set.
 */
final class NativeChromeColors implements ChromeColors {
  @Override
  public void add(Control surface) {}

  @Override
  public void add(Icons set) {}

  @Override
  public void apply(boolean dark) {}

  @Override
  public void dispose() {}
}
