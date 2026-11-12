package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Menu contents with and without the macOS application menu, the placement of every command, and
 * mnemonics per menu: the menu bar titles, every menu and cascade, and the editor, tab and row
 * context menus. The view-built Encoding and Syntax cascades are walked in {@code
 * ToolbarAndMenusTest}.
 */
class MenuTableTest {
  @Test
  void theApplicationMenuTakesAboutExitAndPreferences() {
    assertTrue(MenuTable.commands(CommandGroup.HELP, false).contains(CommandId.ABOUT));
    assertTrue(MenuTable.commands(CommandGroup.FILE, false).contains(CommandId.QUIT));
    assertTrue(MenuTable.commands(CommandGroup.TOOLS, false).contains(CommandId.PREFERENCES));
    assertFalse(MenuTable.commands(CommandGroup.HELP, true).contains(CommandId.ABOUT));
    assertFalse(MenuTable.commands(CommandGroup.FILE, true).contains(CommandId.QUIT));
    assertFalse(MenuTable.commands(CommandGroup.TOOLS, true).contains(CommandId.PREFERENCES));
    assertEquals(
        MenuTable.commands(CommandGroup.VIEW, false), MenuTable.commands(CommandGroup.VIEW, true));
    List<MenuTable.Entry> tools = MenuTable.entries(CommandGroup.TOOLS, true);
    assertEquals(CommandId.DOCUMENT_STATISTICS, tools.get(tools.size() - 1).command());
  }

  @Test
  void everyMenuCommandSitsOnceInItsOwnMenu() {
    for (CommandId command : CommandId.values()) {
      int count = 0;
      for (CommandGroup group : CommandGroup.values()) {
        for (CommandId listed : MenuTable.commands(group, false)) {
          if (listed == command) {
            assertEquals(command.group(), group, command.name());
            count++;
          }
        }
      }
      assertEquals(command.group().menu() ? 1 : 0, count, command.name());
    }
  }

  @Test
  void aCascadesCommandsAreAdjacent() {
    for (CommandGroup group : CommandGroup.values()) {
      List<String> closed = new ArrayList<String>();
      String open = null;
      for (CommandId command : MenuTable.commands(group, false)) {
        String submenu = command.submenu();
        if (submenu != null && !submenu.equals(open)) {
          assertFalse(closed.contains(submenu), group + ": " + submenu + " split by " + command);
        }
        if (open != null && !open.equals(submenu)) {
          closed.add(open);
        }
        open = submenu;
      }
    }
  }

  @Test
  void radioItemsAreCheckCommands() {
    for (CommandId command : MenuTable.RADIO) {
      assertTrue(command.check(), command.name());
      assertTrue(command.submenu() != null, command.name());
    }
  }

  @Test
  void menuBarTitlesHaveDistinctMnemonics() {
    List<String> titles = new ArrayList<String>();
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        titles.add(group.label());
      }
    }
    assertDistinct("menu bar", titles);
    assertEquals('l', mnemonic(CommandGroup.SELECTION.label()));
    assertEquals('s', mnemonic(CommandGroup.SEARCH.label()));
  }

  @Test
  void mnemonicsAreUniquePerMenu() {
    for (boolean appMenu : new boolean[] {false, true}) {
      for (CommandGroup group : CommandGroup.values()) {
        for (Map.Entry<String, List<String>> menu : menus(group, appMenu).entrySet()) {
          assertDistinct(menu.getKey(), menu.getValue());
        }
      }
    }
  }

  @Test
  void contextMenuMnemonicsAreUnique() {
    List<String> editor = new ArrayList<String>();
    for (CommandId command : MenuTable.EDITOR_MENU) {
      if (command != null) {
        editor.add(command.label());
      }
    }
    assertDistinct("editor menu", editor);
    List<String> tab = new ArrayList<String>();
    for (UiText label : MenuTable.TAB_MENU) {
      if (label != null) {
        tab.add(label.text());
      }
    }
    assertDistinct("tab menu", tab);
    List<String> row = new ArrayList<String>();
    for (WorkspaceSelectionInput.Action action : WorkspaceSelectionInput.MENU) {
      if (action != null) {
        row.add(action.label().text());
      }
    }
    assertDistinct("row menu", row);
    assertEquals(WorkspaceSelectionInput.Action.values().length, row.size());
  }

  @Test
  void closeLeftAndRightHaveNoKeys() {
    for (boolean mac : new boolean[] {false, true}) {
      Keymap keymap = Keymap.defaults(mac);
      assertEquals("", keymap.keysText(CommandId.CLOSE_LEFT));
      assertEquals("", keymap.keysText(CommandId.CLOSE_RIGHT));
    }
  }

  /** Fails when two labels share a mnemonic; a label without one is skipped. */
  static void assertDistinct(String menu, List<String> labels) {
    List<Character> seen = new ArrayList<Character>();
    for (String label : labels) {
      Character key = mnemonic(label);
      if (key == null) {
        continue;
      }
      assertFalse(seen.contains(key), menu + ": '" + key + "' again in " + label);
      seen.add(key);
    }
  }

  /**
   * Returns the char after the first single {@code &}, lower case, or null; {@code &&} is a
   * literal.
   */
  static Character mnemonic(String label) {
    for (int i = 0; i < label.length() - 1; i++) {
      if (label.charAt(i) == '&') {
        if (label.charAt(i + 1) != '&') {
          return Character.toLowerCase(label.charAt(i + 1));
        }
        i++;
      }
    }
    return null;
  }

  /** Labels per menu: the group's own menu, then each cascade in it. */
  private static Map<String, List<String>> menus(CommandGroup group, boolean appMenu) {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> top = new ArrayList<String>();
    menus.put(group.label(), top);
    for (CommandId command : MenuTable.commands(group, appMenu)) {
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

  @Test
  void aLabelWithoutAMnemonicHasNone() {
    assertNull(mnemonic("Plain && Simple"));
    assertEquals('s', mnemonic("Plain && &Simple"));
  }
}
