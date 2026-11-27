package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Combo;

/**
 * Draws the selected row of a drop-down combo's open list, read-only or editable, from {@link
 * PlatformTraits#comboListSelection}: {@link Win32ComboListSelection} on win32, {@link
 * NativeComboListSelection} elsewhere. Call on the UI thread.
 */
public interface ComboListSelection {
  /** The running platform's implementation. */
  ComboListSelection PLATFORM = PlatformTraits.PLATFORM.comboListSelection();

  /**
   * Draws the combo's list selection from now on until the combo is disposed; call once, after
   * creating the combo. A {@code SWT.SIMPLE} combo, whose list is always shown, is left as it is.
   *
   * @param combo the combo
   */
  void draw(Combo combo);
}
