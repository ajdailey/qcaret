package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The macOS application menu: About, Preferences and Quit run {@link CommandId#ABOUT}, {@link
 * CommandId#PREFERENCES} and {@link CommandId#QUIT}, so the menu bar leaves them out ({@link
 * io.github.ajdailey.qcaret.app.command.MenuTable}). Hide, Hide Others and Show All stay native. A
 * quit request from the system (Cmd-Q, logout) runs {@link CommandId#QUIT}, with its
 * unsaved-changes prompts, in place of SWT's own disposal. Elsewhere {@link
 * Display#getSystemMenu()} is null and nothing is added.
 */
public final class AppMenu {
  private AppMenu() {}

  /**
   * Reports whether the system supplies the application menu.
   *
   * @param display the Display
   * @return true on cocoa
   */
  public static boolean present(Display display) {
    return "cocoa".equals(SWT.getPlatform()) && display.getSystemMenu() != null;
  }

  /**
   * Adds the listeners when the application menu is present; call once, on the UI thread.
   *
   * @param display the Display
   * @param controller the main window's controller
   * @return true when the listeners were added
   */
  public static boolean hook(Display display, final ApplicationController controller) {
    if (!present(display)) {
      return false;
    }
    Menu menu = display.getSystemMenu();
    for (MenuItem item : menu.getItems()) {
      final CommandId command = command(item.getID());
      if (command != null) {
        item.addListener(
            SWT.Selection,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                controller.execute(command);
              }
            });
      }
    }
    display.addListener(
        SWT.Close,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            controller.execute(CommandId.QUIT);
          }
        });
    return true;
  }

  /**
   * Returns the command an application-menu item runs.
   *
   * @param id the item's {@link MenuItem#getID()}
   * @return the command, or null for an item that stays native
   */
  static CommandId command(int id) {
    return switch (id) {
      case SWT.ID_ABOUT -> CommandId.ABOUT;
      case SWT.ID_PREFERENCES -> CommandId.PREFERENCES;
      case SWT.ID_QUIT -> CommandId.QUIT;
      default -> null;
    };
  }
}
