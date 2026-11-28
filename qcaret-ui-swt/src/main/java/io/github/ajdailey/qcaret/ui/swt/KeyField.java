package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

/**
 * A read-only field that takes the next key pressed in it as a {@link KeyStroke} and shows it as
 * the platform writes it. While it has focus it takes every key stroke but Tab and Shift+Tab
 * ({@link KeyCapture}): Enter and Esc are taken as keys, not as the dialog's default button and
 * close, and a menu item's key as a key, not as its command. Call on the UI thread.
 */
final class KeyField {
  private final Text text;
  private final Keymap keymap;
  private final Runnable changed;
  private KeyStroke stroke;

  /**
   * Creates the field in a grid cell.
   *
   * @param parent the parent, laid out with a grid
   * @param keymap writes the key with the platform's names
   * @param changed runs after each key taken and each {@link #set}
   */
  KeyField(Composite parent, Keymap keymap, Runnable changed) {
    this.keymap = keymap;
    this.changed = changed;
    text = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
    text.setMessage(UiText.PREFERENCES_KEY_CAPTURE.text());
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 160;
    text.setLayoutData(data);
    KeyCapture.install(text);
    text.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            KeyStroke pressed = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
            if (pressed != null) {
              event.doit = false;
              set(pressed);
            }
          }
        });
    text.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_ESCAPE || event.detail == SWT.TRAVERSE_RETURN) {
              event.doit = false;
            }
          }
        });
  }

  /**
   * Shows a key.
   *
   * @param value the key, or null to empty the field
   */
  void set(KeyStroke value) {
    stroke = value;
    text.setText(value == null ? "" : keymap.keyText(value));
    changed.run();
  }

  /**
   * Returns the key shown.
   *
   * @return the key, or null
   */
  KeyStroke stroke() {
    return stroke;
  }

  Text text() {
    return text;
  }
}
