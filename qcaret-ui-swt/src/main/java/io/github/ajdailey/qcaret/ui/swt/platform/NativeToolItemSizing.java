package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.ToolItem;

/** Tool item sizing on win32, cocoa and GTK 3: SWT lays out the image at its size. */
final class NativeToolItemSizing implements ToolItemSizing {
  @Override
  public void keepIconSize(ToolItem item) {}
}
