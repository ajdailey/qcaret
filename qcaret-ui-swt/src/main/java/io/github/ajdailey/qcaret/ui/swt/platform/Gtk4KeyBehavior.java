package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.KeyTarget;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.widgets.Control;

/** Keys on GTK 4: the filter runs the accelerators too, before the focused control. */
final class Gtk4KeyBehavior extends Gtk3KeyBehavior {
  @Override
  public KeyTarget windowTarget(Keymap keymap, KeyStroke stroke, Control focus) {
    return keymap.macros().windowBindingTarget(stroke);
  }
}
