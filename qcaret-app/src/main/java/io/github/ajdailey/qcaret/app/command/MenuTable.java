package io.github.ajdailey.qcaret.app.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The commands each menu-bar menu lists, in {@link CommandId} declaration order. Where the system
 * supplies an application menu (macOS), About, Exit and Preferences sit there and are left out of
 * Help, File and Edit.
 */
public final class MenuTable {
  /** Commands the macOS application menu runs instead of the menu bar. */
  public static final Set<CommandId> APP_MENU_COMMANDS =
      EnumSet.of(CommandId.ABOUT, CommandId.QUIT, CommandId.PREFERENCES);

  private MenuTable() {}

  /**
   * Returns the commands a menu lists.
   *
   * @param group a group whose {@link CommandGroup#menu()} is true
   * @param appMenu true when the system's application menu holds About, Quit and Preferences
   * @return the commands in menu order
   */
  public static List<CommandId> commands(CommandGroup group, boolean appMenu) {
    List<CommandId> commands = new ArrayList<CommandId>();
    for (CommandId command : CommandId.values()) {
      if (command.group() == group && !(appMenu && APP_MENU_COMMANDS.contains(command))) {
        commands.add(command);
      }
    }
    return commands;
  }
}
