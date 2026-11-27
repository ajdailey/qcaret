package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Combo;

/** Combo lists on GTK 3, GTK 4 and cocoa: the native selection stays. */
final class NativeComboListSelection implements ComboListSelection {
  @Override
  public void draw(Combo combo) {}
}
