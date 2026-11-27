package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MacroKeys;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The Macros menu's saved macros: a separator and one item per {@link MacroKeys} entry, after the
 * menu's commands. A macro's key is its item's accelerator, so the items exist from {@link
 * MainMenuBar#build} on rather than from the menu's first showing. Call on the UI thread.
 */
final class SavedMacroItems {
  /** Widget data of the saved macros' items and their separator. */
  static final String SAVED = "qcaret.savedMacro";

  private SavedMacroItems() {}

  /**
   * Appends the saved macros of a keymap to the Macros menu.
   *
   * @param menu the Macros menu
   * @param keymap the keys, with the saved macros
   * @param controller runs a chosen macro
   */
  static void fill(Menu menu, Keymap keymap, final ApplicationController controller) {
    if (keymap.macros().keys().entries().isEmpty()) {
      return;
    }
    new MenuItem(menu, SWT.SEPARATOR).setData(SAVED);
    for (MacroKeys.Entry entry : keymap.macros().keys().entries()) {
      final String name = entry.name();
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      String keys = keymap.macros().keyText(name);
      String label = name.replace("&", "&&");
      item.setText(keys.isEmpty() ? label : label + "\t" + keys);
      KeyStroke accelerator = keymap.macros().accelerator(name);
      if (accelerator != null) {
        item.setAccelerator(SwtKeys.accelerator(accelerator));
      }
      item.setData(SAVED);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              controller.runMacro(name);
            }
          });
    }
  }

  /**
   * Replaces the saved macros' items of the Macros menu with those of a keymap.
   *
   * @param menu the Macros menu
   * @param keymap the keys, with the saved macros
   * @param controller runs a chosen macro
   */
  static void replace(Menu menu, Keymap keymap, ApplicationController controller) {
    for (MenuItem item : menu.getItems()) {
      if (SAVED.equals(item.getData())) {
        item.dispose();
      }
    }
    fill(menu, keymap, controller);
  }

  /**
   * Enables the saved macros' items from {@link CommandStates#savedMacroEnabled}.
   *
   * @param menu the Macros menu, or null
   * @param states the window's command states
   */
  static void sync(Menu menu, CommandStates states) {
    if (menu == null || menu.isDisposed()) {
      return;
    }
    boolean enabled = states.savedMacroEnabled();
    for (MenuItem item : menu.getItems()) {
      if (SAVED.equals(item.getData())
          && (item.getStyle() & SWT.SEPARATOR) == 0
          && item.getEnabled() != enabled) {
        item.setEnabled(enabled);
      }
    }
  }
}
