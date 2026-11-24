package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;

/** Keys on GTK 3: Alt does not type, and the filter runs only the second keys. */
class Gtk3KeyBehavior implements KeyBehavior {
  @Override
  public boolean types(int modifiers) {
    return plainOrAltGr(modifiers);
  }

  @Override
  public CommandId windowCommand(Keymap keymap, KeyStroke stroke, Control focus) {
    return keymap.windowCommand(stroke);
  }

  /**
   * Reports whether modifiers are none or Ctrl+Alt (AltGr).
   *
   * @param modifiers the modifier bits
   * @return true for no modifier or Ctrl+Alt
   */
  static boolean plainOrAltGr(int modifiers) {
    return modifiers == 0 || modifiers == (SWT.CTRL | SWT.ALT);
  }
}
