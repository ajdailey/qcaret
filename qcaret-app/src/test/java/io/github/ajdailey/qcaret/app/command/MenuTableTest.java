package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Menu contents with and without the macOS application menu, and mnemonics per menu. */
class MenuTableTest {
  @Test
  void theApplicationMenuTakesAboutAndExit() {
    assertTrue(MenuTable.commands(CommandGroup.HELP, false).contains(CommandId.ABOUT));
    assertTrue(MenuTable.commands(CommandGroup.FILE, false).contains(CommandId.QUIT));
    assertFalse(MenuTable.commands(CommandGroup.HELP, true).contains(CommandId.ABOUT));
    assertFalse(MenuTable.commands(CommandGroup.FILE, true).contains(CommandId.QUIT));
    assertEquals(
        MenuTable.commands(CommandGroup.VIEW, false), MenuTable.commands(CommandGroup.VIEW, true));
  }

  @Test
  void mnemonicsAreUniquePerMenu() {
    for (CommandGroup group : CommandGroup.values()) {
      for (Map.Entry<String, List<String>> menu : menus(group).entrySet()) {
        List<Character> seen = new ArrayList<Character>();
        for (String label : menu.getValue()) {
          int at = label.indexOf('&');
          if (at < 0) {
            continue;
          }
          char key = label.toLowerCase(Locale.ROOT).charAt(at + 1);
          assertFalse(seen.contains(key), menu.getKey() + ": '" + key + "' again in " + label);
          seen.add(key);
        }
      }
    }
  }

  /** Labels per menu: the group's own menu, then each cascade in it. */
  private static Map<String, List<String>> menus(CommandGroup group) {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> top = new ArrayList<String>();
    menus.put(group.label(), top);
    for (CommandId command : MenuTable.commands(group, false)) {
      String submenu = command.submenu();
      if (submenu == null) {
        top.add(command.label());
        continue;
      }
      if (!menus.containsKey(submenu)) {
        top.add(submenu);
        menus.put(submenu, new ArrayList<String>());
      }
      menus.get(submenu).add(command.label());
    }
    return menus;
  }
}
