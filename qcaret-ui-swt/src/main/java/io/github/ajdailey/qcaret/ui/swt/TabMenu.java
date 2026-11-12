package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.MenuTable;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.CommandStates;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.TabInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * A tab's context menu, laid out by {@link MenuTable#TAB_MENU}. Each item acts on the tab the menu
 * opened over, not the selected one. One popup, owned by the shell, serves every tab; each show
 * replaces its items. Call on the UI thread.
 */
final class TabMenu {
  private final Shell shell;
  private final ApplicationController controller;
  private final CommandStates states;
  private Menu menu;

  /**
   * Creates the owner; {@link #show} builds the popup.
   *
   * @param shell the window's shell, which disposes the popup
   * @param controller the window's controller
   * @param states the items' enabled state
   */
  TabMenu(Shell shell, ApplicationController controller, CommandStates states) {
    this.shell = shell;
    this.controller = controller;
    this.states = states;
  }

  /**
   * Shows the popup for a tab.
   *
   * @param tab the tab's input
   * @param x display x
   * @param y display y
   * @return the popup, already visible
   */
  Menu show(final TabInput tab, int x, int y) {
    if (menu == null || menu.isDisposed()) {
      menu = new Menu(shell, SWT.POP_UP);
    }
    for (MenuItem old : menu.getItems()) {
      old.dispose();
    }
    CommandStates.TabItems state = states.tabItems(tab);
    for (final UiText label : MenuTable.TAB_MENU) {
      if (label == null) {
        new MenuItem(menu, SWT.SEPARATOR);
        continue;
      }
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(label.text());
      item.setEnabled(enabled(label, state));
      item.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              run(label, tab);
            }
          });
    }
    menu.setLocation(x, y);
    menu.setVisible(true);
    return menu;
  }

  private static boolean enabled(UiText label, CommandStates.TabItems state) {
    return switch (label) {
      case COMMAND_CLOSE_OTHERS -> state.closeOthers();
      case TAB_CLOSE_LEFT -> state.closeLeft();
      case TAB_CLOSE_RIGHT -> state.closeRight();
      case TAB_COPY_PATH, TAB_COPY_FILE_NAME, TAB_OPEN_FOLDER -> state.file();
      default -> true;
    };
  }

  private void run(UiText label, TabInput tab) {
    switch (label) {
      case COMMAND_CLOSE -> tab.closeRequested();
      case COMMAND_CLOSE_OTHERS -> tab.closeOthersRequested();
      case TAB_CLOSE_LEFT -> tab.closeSideRequested(true);
      case TAB_CLOSE_RIGHT -> tab.closeSideRequested(false);
      case TAB_CLOSE_ALL -> controller.execute(CommandId.CLOSE_ALL);
      case TAB_COPY_PATH -> tab.copyPathRequested(false);
      case TAB_COPY_FILE_NAME -> tab.copyPathRequested(true);
      case TAB_OPEN_FOLDER -> controller.openContainingFolder(tab);
      case COMMAND_MOVE_TO_NEW_WINDOW -> controller.moveTabToNewWindow(tab, null, null);
      default -> throw new IllegalArgumentException(label.name());
    }
  }
}
