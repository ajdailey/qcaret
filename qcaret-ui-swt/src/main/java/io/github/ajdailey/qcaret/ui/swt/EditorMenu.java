package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
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
 * The context menu of a window's text views, laid out by {@link MenuTable#editorEntries} with the
 * cascades of {@link MenuTable#editorSubmenu}. One popup, built on its first show and again after a
 * key change, serves every view, because building a menu per view stalls a file open under GTK 4.
 * Each item runs its command in the view the menu opened over; a command the view does not run,
 * such as Format, goes to the window's controller, whose active view is the one the menu opened
 * over. The view is held weakly so the popup does not keep a closed document. Call on the UI
 * thread.
 */
final class EditorMenu {
  private final Shell shell;
  private final ApplicationController controller;
  private final CommandStates states;
  private final MenuIcons icons;
  private Keymap keymap;
  private Menu menu;
  private WeakReference<EditorInput> target = new WeakReference<EditorInput>(null);

  /**
   * Creates the owner; {@link #show} builds the popup.
   *
   * @param shell the window's shell, which disposes the popup
   * @param controller runs the commands the view does not
   * @param states the commands' enabled state
   * @param icons the items' icons
   * @param keymap the keys the items show
   */
  EditorMenu(
      Shell shell,
      ApplicationController controller,
      CommandStates states,
      MenuIcons icons,
      Keymap keymap) {
    this.shell = shell;
    this.controller = controller;
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
    sync(menu);
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

  /** Enables each item, and each cascade from its first command, as {@link CommandStates} says. */
  private void sync(Menu items) {
    for (MenuItem item : items.getItems()) {
      if (item.getData() instanceof CommandId command) {
        item.setEnabled(states.enabled(command));
        icons.showState(item);
      } else if (item.getMenu() != null) {
        sync(item.getMenu());
      }
    }
  }

  private Menu build() {
    Menu popup = new Menu(shell, SWT.POP_UP);
    Menu into = popup;
    String cascade = null;
    for (MenuTable.Entry entry : MenuTable.editorEntries()) {
      final CommandId command = entry.command();
      String submenu = MenuTable.editorSubmenu(command);
      if (submenu == null) {
        into = popup;
        cascade = null;
      } else if (!submenu.equals(cascade)) {
        if (entry.separatorBefore()) {
          new MenuItem(popup, SWT.SEPARATOR);
        }
        MenuItem holder = new MenuItem(popup, SWT.CASCADE);
        holder.setText(submenu);
        into = new Menu(shell, SWT.DROP_DOWN);
        holder.setMenu(into);
        cascade = submenu;
      }
      if (entry.separatorBefore() && into.getItemCount() > 0) {
        new MenuItem(into, SWT.SEPARATOR);
      }
      MenuItem item = new MenuItem(into, SWT.PUSH);
      String keys = keymap.menuKeyText(command);
      item.setText(keys.isEmpty() ? command.label() : command.label() + "\t" + keys);
      item.setData(command);
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              EditorInput input = target.get();
              if (input != null && !input.execute(command)) {
                controller.execute(command);
              }
            }
          });
    }
    icons.decorate(popup);
    return popup;
  }
}
