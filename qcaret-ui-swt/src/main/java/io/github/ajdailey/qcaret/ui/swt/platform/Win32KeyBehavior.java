package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.KeyTarget;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Control;

/**
 * Keys on win32: a key in a {@code Browser} reaches its {@code KeyDown} listeners and no menu
 * accelerator runs, so the filter runs every binding there. A key a listener consumes is marked
 * handled, and WebView2 does not act on it.
 */
final class Win32KeyBehavior implements KeyBehavior {
  @Override
  public boolean types(int modifiers) {
    return Gtk3KeyBehavior.plainOrAltGr(modifiers);
  }

  @Override
  public KeyTarget windowTarget(Keymap keymap, KeyStroke stroke, Control focus) {
    return focus instanceof Browser
        ? keymap.macros().windowBindingTarget(stroke)
        : keymap.macros().windowTarget(stroke);
  }
}
