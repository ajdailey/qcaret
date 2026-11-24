package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Control;

/** Labels on win32 and GTK: the name alone, so nothing is added. */
final class NativeAccessibleLabels implements AccessibleLabels {
  @Override
  public void describe(Control control, AccessibleListener description) {}

  @Override
  public void describeKeepingRole(Control control, AccessibleListener description) {}
}
