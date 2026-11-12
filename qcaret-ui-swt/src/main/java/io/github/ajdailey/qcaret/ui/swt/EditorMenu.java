package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import java.lang.ref.WeakReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * The context menu of a window's text views, laid out by {@link MenuTable#EDITOR_MENU}. One popup,
 * built on its first show and again after a key change, serves every view, because building a menu
 * per view stalls a file open under GTK 4. Each item runs its command in the view the menu opened
 * over. The view is held weakly so the popup does not keep a closed document. Call on the UI
 * thread.
 */
final class EditorMenu {
  private final Shell shell;
  private final CommandStates states;
  private final MenuIcons icons;
  private Keymap keymap;
  private Menu menu;
  private WeakReference<EditorInput> target = new WeakReference<EditorInput>(null);

  /**
   * Creates the owner; {@link #show} builds the popup.
   *
   * @param shell the window's shell, which disposes the popup
   * @param states the commands' enabled state
   * @param icons the items' icons
   * @param keymap the keys the items show
   */
  EditorMenu(Shell shell, CommandStates states, MenuIcons icons, Keymap keymap) {
    this.shell = shell;
    this.states = states;
    this.icons = icons;
    this.keymap = keymap;
  }

  /**
   * Sets the keys the items show.
   *
   * @param bindings the key bindings
   */
  void setKeymap(Keymap bindings) {
    keymap = bindings;
    if (menu != null) {
      menu.dispose();
      menu = null;
    }
  }

  /**
   * Shows the popup for a view.
   *
   * @param input the view's input
   * @param x display x
   * @param y display y
   * @return the popup, already visible
   */
  Menu show(EditorInput input, int x, int y) {
    target = new WeakReference<EditorInput>(input);
    if (menu == null) {
      menu = build();
    }
    for (MenuItem item : menu.getItems()) {
      if (item.getData() instanceof CommandId command) {
        item.setEnabled(states.enabled(command));
        icons.showState(item);
      }
    }
    menu.setLocation(x, y);
    menu.setVisible(true);
    return menu;
  }

  /**
   * Returns the popup.
   *
   * @return the popup, or null before {@link #show} and after a key change
   */
  Menu menu() {
    return menu;
  }

  private Menu build() {
    Menu popup = new Menu(shell, SWT.POP_UP);
    for (final CommandId command : MenuTable.EDITOR_MENU) {
      if (command == null) {
        new MenuItem(popup, SWT.SEPARATOR);
        continue;
      }
      MenuItem item = new MenuItem(popup, SWT.PUSH);
      String keys = keymap.menuKeyText(command);
      item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
      item.setData(command);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              EditorInput input = target.get();
              if (input != null) {
                input.execute(command);
              }
            }
          });
    }
    icons.decorate(popup);
    return popup;
  }
}
