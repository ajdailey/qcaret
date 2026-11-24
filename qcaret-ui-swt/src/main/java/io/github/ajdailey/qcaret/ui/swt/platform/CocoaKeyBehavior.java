package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;

/** Keys on cocoa: Alt (Option) and a key types the key's character. */
final class CocoaKeyBehavior implements KeyBehavior {
  @Override
  public boolean types(int modifiers) {
    return Gtk3KeyBehavior.plainOrAltGr(modifiers) || modifiers == SWT.ALT;
  }

  @Override
  public CommandId windowCommand(Keymap keymap, KeyStroke stroke, Control focus) {
    return keymap.windowCommand(stroke);
  }
}
