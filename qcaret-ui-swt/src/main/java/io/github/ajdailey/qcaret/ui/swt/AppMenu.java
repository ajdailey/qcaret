package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the macOS application menu and the system request ({@link MenuBehavior#hookAppMenu}): About,
 * Preferences and Quit run {@link CommandId#ABOUT}, {@link CommandId#PREFERENCES} and {@link
 * CommandId#QUIT} in the window activated last, so the menu bar leaves them out ({@link
 * MenuBehavior#systemCommands}). A quit request from the system (Cmd-Q, logout) runs {@link
 * WindowSet#requestQuit}, with its unsaved-changes prompts in every window, in place of SWT's own
 * disposal. A file the system opens with QCaret (Open With, a Dock drop) goes to {@link
 * WindowSet#openFile}; a name that does not parse is logged.
 */
public final class AppMenu {
  private static final Logger LOG = LoggerFactory.getLogger(AppMenu.class);

  private AppMenu() {}

  /**
   * Hooks the application menu where the platform has one; call once, on the UI thread.
   *
   * @param display the Display
   * @param set the windows of the instance
   */
  public static void hook(Display display, final WindowSet set) {
    MenuBehavior.PLATFORM.hookAppMenu(
        display,
        new MenuBehavior.AppMenuActions() {
          @Override
          public void select(int id) {
            CommandId command = command(id);
            if (command != null) {
              run(set, command);
            }
          }

          @Override
          public void quit() {
            set.requestQuit();
          }

          @Override
          public void open(String file) {
            AppMenu.open(set, file);
          }
        });
  }

  /**
   * Returns the command an application-menu item runs.
   *
   * @param id the item's {@code MenuItem.getID()}
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

  /**
   * Runs an application-menu command in the window activated last. Without a window, Quit still
   * quits.
   *
   * @param set the windows of the instance
   * @param command the command
   */
  static void run(WindowSet set, CommandId command) {
    ApplicationController window = set.target();
    if (window != null) {
      window.execute(command);
    } else if (command == CommandId.QUIT) {
      set.requestQuit();
    }
  }

  /**
   * Opens a file the system named; null, an empty name and a name that does not parse open nothing,
   * since an exception here reaches the event loop.
   *
   * @param set the windows of the instance
   * @param name the event's text
   */
  static void open(WindowSet set, String name) {
    if (name == null || name.isEmpty()) {
      return;
    }
    try {
      set.openFile(Path.of(name));
    } catch (InvalidPathException e) {
      LOG.warn("Cannot open {}: {}", LogText.oneLine(name), LogText.oneLine(e.getMessage()));
    }
  }
}
