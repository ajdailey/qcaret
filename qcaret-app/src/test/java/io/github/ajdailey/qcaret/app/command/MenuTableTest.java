package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Menu contents with and without the macOS application menu, the placement of every command, the
 * editor, dock and status bar menus' order, and mnemonics per menu: the menu bar titles, every menu
 * and cascade, and the editor, tab, row, results, bookmarks and status bar menus. Every item has a
 * mnemonic, unique in its menu. The view-built Encoding and Syntax cascades are walked in {@code
 * ToolbarAndMenusTest}.
 */
class MenuTableTest {
  @Test
  void theApplicationMenuTakesAboutExitAndPreferences() {
    assertTrue(MenuTable.commands(CommandGroup.HELP, Set.of()).contains(CommandId.ABOUT));
    assertTrue(MenuTable.commands(CommandGroup.FILE, Set.of()).contains(CommandId.QUIT));
    assertTrue(MenuTable.commands(CommandGroup.TOOLS, Set.of()).contains(CommandId.PREFERENCES));
    assertFalse(
        MenuTable.commands(CommandGroup.HELP, MenuTable.APP_MENU_COMMANDS)
            .contains(CommandId.ABOUT));
    assertFalse(
        MenuTable.commands(CommandGroup.FILE, MenuTable.APP_MENU_COMMANDS)
            .contains(CommandId.QUIT));
    assertFalse(
        MenuTable.commands(CommandGroup.TOOLS, MenuTable.APP_MENU_COMMANDS)
            .contains(CommandId.PREFERENCES));
    assertEquals(
        MenuTable.commands(CommandGroup.VIEW, Set.of()),
        MenuTable.commands(CommandGroup.VIEW, MenuTable.APP_MENU_COMMANDS));
    List<MenuTable.Entry> tools =
        MenuTable.entries(CommandGroup.TOOLS, MenuTable.APP_MENU_COMMANDS);
    assertEquals(CommandId.DOCUMENT_STATISTICS, tools.get(tools.size() - 1).command());
  }

  @Test
  void minimizeAndZoomLeadTheWindowMenuUnlessTheTitleBarTakesThem() {
    List<MenuTable.Entry> mac = MenuTable.entries(CommandGroup.WINDOW, MenuTable.APP_MENU_COMMANDS);
    assertEquals(CommandId.MINIMIZE, mac.get(0).command());
    assertEquals(CommandId.ZOOM_WINDOW, mac.get(1).command());
    assertEquals(new MenuTable.Entry(CommandId.NEW_WINDOW, true), mac.get(2));
    List<MenuTable.Entry> others =
        MenuTable.entries(CommandGroup.WINDOW, MenuTable.TITLE_BAR_COMMANDS);
    assertEquals(new MenuTable.Entry(CommandId.NEW_WINDOW, false), others.get(0));
    assertEquals(mac.subList(3, mac.size()), others.subList(1, others.size()));
  }

  @Test
  void everyMenuCommandSitsOnceInItsOwnMenu() {
    for (CommandId command : CommandId.values()) {
      int count = 0;
      for (CommandGroup group : CommandGroup.values()) {
        for (CommandId listed : MenuTable.commands(group, Set.of())) {
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
      for (CommandId command : MenuTable.commands(group, Set.of())) {
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
    for (Map.Entry<String, List<String>> menu : editorMenus().entrySet()) {
      assertDistinct(menu.getKey(), menu.getValue());
    }
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
    for (Map.Entry<String, List<String>> menu : dockMenus().entrySet()) {
      assertDistinct(menu.getKey(), menu.getValue());
    }
  }

  @Test
  void everyItemHasAMnemonic() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    for (CommandGroup group : CommandGroup.values()) {
      menus.putAll(menus(group, false));
    }
    menus.putAll(editorMenus());
    menus.putAll(dockMenus());
    List<String> tab = new ArrayList<String>();
    for (UiText label : MenuTable.TAB_MENU) {
      if (label != null) {
        tab.add(label.text());
      }
    }
    menus.put("tab menu", tab);
    List<String> row = new ArrayList<String>();
    for (WorkspaceSelectionInput.Action action : WorkspaceSelectionInput.MENU) {
      if (action != null) {
        row.add(action.label().text());
      }
    }
    menus.put("row menu", row);
    List<String> missing = new ArrayList<String>();
    for (Map.Entry<String, List<String>> menu : menus.entrySet()) {
      for (String label : menu.getValue()) {
        if (mnemonic(label) == null) {
          missing.add(menu.getKey() + " > " + label);
        }
      }
    }
    assertEquals(new ArrayList<String>(), missing);
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
    for (CommandId command :
        MenuTable.commands(group, appMenu ? MenuTable.APP_MENU_COMMANDS : Set.<CommandId>of())) {
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

  /** Labels of the editor menu and each of its cascades. */
  private static Map<String, List<String>> editorMenus() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> top = new ArrayList<String>();
    menus.put("editor menu", top);
    for (MenuTable.Entry entry : MenuTable.editorEntries()) {
      CommandId command = entry.command();
      String submenu = MenuTable.editorSubmenu(command);
      if (submenu == null) {
        top.add(command.label());
        continue;
      }
      if (!menus.containsKey("editor > " + submenu)) {
        top.add(submenu);
        menus.put("editor > " + submenu, new ArrayList<String>());
      }
      menus.get("editor > " + submenu).add(command.label());
    }
    return menus;
  }

  /** Labels of the results, bookmarks and status bar menus. */
  private static Map<String, List<String>> dockMenus() {
    Map<String, List<String>> menus = new LinkedHashMap<String, List<String>>();
    List<String> results = new ArrayList<String>();
    for (ResultsInput.RowAction action : ResultsInput.MENU) {
      if (action != null) {
        results.add(action.label().text());
      }
    }
    menus.put("results menu", results);
    List<String> bookmarks = new ArrayList<String>();
    for (BookmarksInput.RowAction action : BookmarksInput.MENU) {
      if (action != null) {
        bookmarks.add(action.label().text());
      }
    }
    menus.put("bookmarks menu", bookmarks);
    List<String> endings = new ArrayList<String>();
    for (CommandId command : MenuTable.LINE_ENDING_MENU) {
      endings.add(command.label());
    }
    menus.put("line-ending menu", endings);
    List<String> encodings = new ArrayList<String>();
    for (UiText label : MenuTable.ENCODING_MENU) {
      encodings.add(label.text());
    }
    menus.put("encoding menu", encodings);
    return menus;
  }

  @Test
  void theEditorMenuEndsWithTheEditMenusCascadesAndToggleLineComment() {
    List<CommandId> edit = MenuTable.commands(CommandGroup.EDIT, Set.of());
    List<String> top = editorMenus().get("editor menu");
    assertEquals(
        List.of(
            UiText.MENU_FORMAT.text(),
            UiText.MENU_ENCODE_DECODE.text(),
            CommandId.TOGGLE_LINE_COMMENT.label(),
            UiText.MENU_CONVERT_CASE.text()),
        top.subList(top.size() - 4, top.size()));
    assertEquals(CommandId.OPEN_PATH_AT_CARET.label(), top.get(top.size() - 5));
    assertEquals(CommandId.TOGGLE_FOLD.label(), top.get(top.size() - 6));
    for (UiText cascade :
        new UiText[] {UiText.MENU_FORMAT, UiText.MENU_ENCODE_DECODE, UiText.MENU_CONVERT_CASE}) {
      List<CommandId> inEdit = new ArrayList<CommandId>();
      for (CommandId command : edit) {
        if (cascade.text().equals(command.submenu())) {
          inEdit.add(command);
        }
      }
      List<CommandId> inEditor = new ArrayList<CommandId>();
      List<Boolean> editorSeparators = new ArrayList<Boolean>();
      for (MenuTable.Entry entry : MenuTable.editorEntries()) {
        if (cascade.text().equals(MenuTable.editorSubmenu(entry.command()))) {
          inEditor.add(entry.command());
          editorSeparators.add(entry.separatorBefore());
        }
      }
      List<Boolean> editSeparators = new ArrayList<Boolean>();
      for (MenuTable.Entry entry : MenuTable.entries(CommandGroup.EDIT, Set.of())) {
        if (cascade.text().equals(entry.command().submenu())) {
          editSeparators.add(entry.separatorBefore());
        }
      }
      assertFalse(inEdit.isEmpty(), cascade.name());
      assertEquals(inEdit, inEditor, cascade.name());
      assertEquals(
          editSeparators.subList(1, editSeparators.size()),
          editorSeparators.subList(1, editorSeparators.size()),
          cascade.name());
    }
  }

  @Test
  void theStatusAndDockMenusListTheirItemsInOrder() {
    assertEquals(
        List.of(CommandId.LINE_ENDING_LF, CommandId.LINE_ENDING_CRLF, CommandId.LINE_ENDING_CR),
        MenuTable.LINE_ENDING_MENU);
    assertTrue(MenuTable.RADIO.containsAll(MenuTable.LINE_ENDING_MENU));
    assertEquals(
        List.of(UiText.MENU_REOPEN_ENCODING, UiText.MENU_SAVE_ENCODING), MenuTable.ENCODING_MENU);
    assertEquals(
        Arrays.asList(
            ResultsInput.RowAction.OPEN,
            ResultsInput.RowAction.COPY,
            ResultsInput.RowAction.COPY_ALL,
            null,
            ResultsInput.RowAction.RERUN,
            ResultsInput.RowAction.PIN,
            ResultsInput.RowAction.REMOVE_SET,
            null,
            ResultsInput.RowAction.EXPAND_ALL,
            ResultsInput.RowAction.COLLAPSE_ALL),
        ResultsInput.MENU);
    assertEquals(
        Arrays.asList(
            BookmarksInput.RowAction.GO_TO,
            BookmarksInput.RowAction.EDIT,
            BookmarksInput.RowAction.COPY_LINE,
            BookmarksInput.RowAction.REMOVE,
            null,
            BookmarksInput.RowAction.CLEAR_ALL),
        BookmarksInput.MENU);
  }

  @Test
  void aLabelWithoutAMnemonicHasNone() {
    assertNull(mnemonic("Plain && Simple"));
    assertEquals('s', mnemonic("Plain && &Simple"));
  }
}
