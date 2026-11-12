package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The default bindings on both key-name platforms. */
class KeymapTest {
  @Test
  void noStrokeIsBoundTwice() {
    assertEquals(List.of(), Keymap.defaults(false).duplicates());
    assertEquals(List.of(), Keymap.defaults(true).duplicates());
  }

  @Test
  void onlyWindowBindingsBecomeAccelerators() {
    Keymap keymap = Keymap.defaults(false);
    assertEquals(
        KeyStroke.of(KeyStroke.PRIMARY | KeyStroke.SHIFT, 's'),
        keymap.accelerator(CommandId.SAVE_AS));
    assertNull(keymap.accelerator(CommandId.COPY));
    assertNull(keymap.accelerator(CommandId.UNDO));
    assertEquals(CommandId.COPY, keymap.editorCommand(KeyStroke.of(KeyStroke.PRIMARY, 'c')));
    assertNull(keymap.editorCommand(KeyStroke.of(KeyStroke.PRIMARY, 's')));
  }

  @Test
  void aSecondWindowKeyRunsFromTheWindowFilter() {
    Keymap keymap = Keymap.defaults(false);
    assertNull(keymap.windowCommand(KeyStroke.of(KeyStroke.PRIMARY, '=')));
    assertEquals(
        CommandId.ZOOM_IN,
        keymap.windowCommand(new KeyStroke(KeyStroke.PRIMARY, KeyStroke.KEYPAD_ADD)));
  }

  @Test
  void aMenuShowsACommandsFirstKeyOnly() {
    Keymap keymap = Keymap.defaults(false);
    assertEquals("Ctrl+X", keymap.menuKeyText(CommandId.CUT));
    assertEquals("Ctrl+Shift+Z", keymap.menuKeyText(CommandId.REDO));
    assertEquals("", keymap.menuKeyText(CommandId.RELOAD));
  }

  @Test
  void keyNamesFollowThePlatform() {
    assertEquals("Ctrl+Shift+Z, Ctrl+Y", Keymap.defaults(false).keysText(CommandId.REDO));
    assertEquals("Cmd+Shift+Z", Keymap.defaults(true).keysText(CommandId.REDO));
    assertTrue(Keymap.defaults(false).keysText(CommandId.PASTE).contains("Shift+Insert"));
    assertFalse(Keymap.defaults(true).keysText(CommandId.PASTE).contains("Insert"));
  }

  @Test
  void theReferenceListsMenusThenTheEditorKeys() {
    String reference = Keymap.defaults(false).reference();
    assertTrue(reference.startsWith("File\n  New\tCtrl+N\n"), reference);
    assertTrue(reference.contains("Help\n  Keyboard Shortcuts\tF1\n"), reference);
    assertTrue(reference.contains("Editor\n  Caret Left\tLeft\n"), reference);
    assertTrue(
        reference.endsWith(
            "  New Line\tEnter, Shift+Enter\n"
                + "  Delete Previous Char\tBackspace, Shift+Backspace\n"
                + "  Delete Next Char\tDelete\n"
                + "  Cancel Running Work, Extra Carets or Find Bar\tEsc\n"),
        reference);
  }

  @Test
  void theReferenceFilterKeepsMatchingRowsUnderTheirGroup() {
    String filtered = Keymap.defaults(false).reference("BLOCK select");
    assertEquals(
        "Editor\n"
            + "  Block Select Left\tAlt+Shift+Left\n"
            + "  Block Select Right\tAlt+Shift+Right\n"
            + "  Block Select Up\tAlt+Shift+Up\n"
            + "  Block Select Down\tAlt+Shift+Down\n",
        filtered);
    assertEquals("", Keymap.defaults(false).reference("no such command"));
  }

  /**
   * Line transforms, Insert Date and Time, Document Statistics, Title Case and Swap Case stay menu
   * only.
   */
  private static final Set<CommandId> MENU_ONLY =
      EnumSet.of(
          CommandId.SORT_LINES_ASCENDING,
          CommandId.SORT_LINES_DESCENDING,
          CommandId.SORT_LINES_IGNORE_CASE,
          CommandId.REMOVE_DUPLICATE_LINES,
          CommandId.TRIM_TRAILING_WHITESPACE,
          CommandId.WRAP_PARAGRAPH,
          CommandId.INSERT_DATE_TIME,
          CommandId.DOCUMENT_STATISTICS,
          CommandId.TITLE_CASE,
          CommandId.SWAP_CASE);

  @Test
  void everyEditingCommandHasAKey() {
    Keymap keymap = Keymap.defaults(true);
    for (CommandId command : CommandId.values()) {
      boolean editing =
          command.group() == CommandGroup.EDIT || command.group() == CommandGroup.EDITOR;
      if (editing && !command.check() && !MENU_ONLY.contains(command)) {
        assertFalse(keymap.keysText(command).isEmpty(), command.name());
      }
    }
  }

  /** D4: IBus takes Ctrl+Shift+U on Linux; Option+Shift+Left and Right select words on macOS. */
  @Test
  void platformCollisionsLeaveTheirKeysOut() {
    for (boolean mac : new boolean[] {false, true}) {
      for (boolean linux : new boolean[] {false, true}) {
        assertEquals(List.of(), Keymap.defaults(mac, linux).duplicates(), mac + " " + linux);
      }
    }
    assertEquals("", Keymap.defaults(false, true).keysText(CommandId.UPPER_CASE));
    assertEquals("Ctrl+Shift+U", Keymap.defaults(false, false).keysText(CommandId.UPPER_CASE));
    assertEquals("Alt+Shift+Left", Keymap.defaults(false).keysText(CommandId.BLOCK_LEFT));
    assertEquals("Cmd+Option+Shift+Left", Keymap.defaults(true).keysText(CommandId.BLOCK_LEFT));
    assertTrue(
        Keymap.defaults(true).keysText(CommandId.SELECT_WORD_LEFT).contains("Option+Shift+Left"));
  }

  /**
   * Add Caret avoids Ctrl+Alt+Up and Down (workspace switching on Linux) and macOS's block keys; F6
   * focuses the other view as a window key, so its menu item shows it.
   */
  @Test
  void addCaretAndFocusOtherViewKeys() {
    for (boolean linux : new boolean[] {false, true}) {
      Keymap keymap = Keymap.defaults(false, linux);
      assertEquals("Ctrl+Shift+Up", keymap.keysText(CommandId.ADD_CARET_ABOVE));
      assertEquals("Ctrl+Shift+Down", keymap.keysText(CommandId.ADD_CARET_BELOW));
      assertNull(
          keymap.editorCommand(
              new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT | KeyStroke.SHIFT, KeyStroke.UP)));
    }
    Keymap mac = Keymap.defaults(true);
    assertEquals("Ctrl+Shift+Up", mac.keysText(CommandId.ADD_CARET_ABOVE));
    assertEquals(
        CommandId.ADD_CARET_BELOW,
        mac.editorCommand(new KeyStroke(KeyStroke.CONTROL | KeyStroke.SHIFT, KeyStroke.DOWN)));
    assertEquals(
        CommandId.BLOCK_UP,
        mac.editorCommand(
            new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT | KeyStroke.SHIFT, KeyStroke.UP)));
    for (boolean onMac : new boolean[] {false, true}) {
      Keymap keymap = Keymap.defaults(onMac);
      KeyStroke f6 = new KeyStroke(0, KeyStroke.F1 + 5);
      assertEquals(f6, keymap.accelerator(CommandId.FOCUS_OTHER_VIEW));
      assertEquals("F6", keymap.keysText(CommandId.FOCUS_OTHER_VIEW));
    }
  }

  /**
   * Quick Open is Mod+P beside the palette's Mod+Shift+P, Switch Document Mod+Shift+O. Go Back and
   * Go Forward are window keys: Alt+Left and Alt+Right, which no editor key uses off macOS, and
   * Control+Command+Left and Right on macOS, where Option+Left and Right move by word.
   */
  @Test
  void quickOpenAndHistoryKeys() {
    for (boolean mac : new boolean[] {false, true}) {
      for (boolean linux : new boolean[] {false, true}) {
        Keymap keymap = Keymap.defaults(mac, linux);
        assertEquals(List.of(), keymap.duplicates(), mac + " " + linux);
        assertEquals(
            KeyStroke.of(KeyStroke.PRIMARY, 'p'), keymap.accelerator(CommandId.QUICK_OPEN));
        assertEquals(
            KeyStroke.of(KeyStroke.PRIMARY | KeyStroke.SHIFT, 'o'),
            keymap.accelerator(CommandId.SWITCH_DOCUMENT));
        int back = mac ? KeyStroke.CONTROL | KeyStroke.PRIMARY : KeyStroke.ALT;
        assertEquals(new KeyStroke(back, KeyStroke.LEFT), keymap.accelerator(CommandId.GO_BACK));
        assertEquals(
            new KeyStroke(back, KeyStroke.RIGHT), keymap.accelerator(CommandId.GO_FORWARD));
        assertNull(keymap.editorCommand(new KeyStroke(back, KeyStroke.LEFT)));
      }
    }
    assertEquals("Ctrl+P", Keymap.defaults(false).keysText(CommandId.QUICK_OPEN));
    assertEquals("Alt+Left", Keymap.defaults(false).keysText(CommandId.GO_BACK));
  }

  /**
   * Edit Bookmark Comment takes Mod+Shift+F2 beside the other bookmark keys: no default of GNOME,
   * KDE, Windows or macOS uses it, unlike Alt+F2 (GNOME and KDE run dialogs) and Ctrl+F2 (macOS
   * menu bar focus).
   */
  /**
   * Select Line and Transpose take Control on macOS, where Command+L is Go to Line; Delete Word
   * takes Option there; Undo and Redo Selection, Title Case and Swap Case have no key.
   */
  @Test
  void selectionAndWordCommandKeysPerPlatform() {
    Keymap other = Keymap.defaults(false, false);
    Keymap mac = Keymap.defaults(true, false);
    String[][] expected = {
      {"SELECT_ALL_OCCURRENCES", "Alt+Enter", "Option+Enter"},
      {"SKIP_OCCURRENCE", "Ctrl+K", "Cmd+K"},
      {"EXPAND_SELECTION", "Ctrl+Shift+Space", "Cmd+Shift+Space"},
      {"SELECT_LINE", "Ctrl+L", "Ctrl+L"},
      {"TRANSPOSE", "Ctrl+T", "Ctrl+T"},
      {"DELETE_WORD_BACK", "Ctrl+Backspace", "Option+Backspace"},
      {"DELETE_WORD_FORWARD", "Ctrl+Delete", "Option+Delete"},
      {"UNDO_SELECTION", "", ""},
      {"REDO_SELECTION", "", ""},
      {"TITLE_CASE", "", ""},
      {"SWAP_CASE", "", ""}
    };
    for (String[] row : expected) {
      CommandId command = CommandId.valueOf(row[0]);
      assertEquals(row[1], other.keysText(command), row[0]);
      assertEquals(row[1], Keymap.defaults(false, true).keysText(command), row[0]);
      assertEquals(row[2], mac.keysText(command), row[0]);
    }
  }

  @Test
  void editBookmarkCommentTakesModShiftF2() {
    for (boolean mac : new boolean[] {false, true}) {
      for (boolean linux : new boolean[] {false, true}) {
        Keymap keymap = Keymap.defaults(mac, linux);
        KeyStroke stroke = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.SHIFT, KeyStroke.F1 + 1);
        assertEquals(CommandId.EDIT_BOOKMARK_COMMENT, keymap.editorCommand(stroke));
        assertNull(keymap.editorCommand(new KeyStroke(KeyStroke.ALT, KeyStroke.F1 + 1)));
        if (mac) {
          assertNull(keymap.editorCommand(new KeyStroke(KeyStroke.CONTROL, KeyStroke.F1 + 1)));
        }
      }
    }
    assertEquals("Ctrl+Shift+F2", Keymap.defaults(false).keysText(CommandId.EDIT_BOOKMARK_COMMENT));
    assertEquals("Cmd+Shift+F2", Keymap.defaults(true).keysText(CommandId.EDIT_BOOKMARK_COMMENT));
  }

  @Test
  void aStrokeRoundTripsItsStoredForm() {
    for (Binding binding : Keymap.defaults(true, false).bindings()) {
      assertEquals(binding.stroke(), KeyStroke.parse(binding.stroke().stored()));
    }
    assertEquals(
        new KeyStroke(KeyStroke.PRIMARY | KeyStroke.SHIFT, 'k'), KeyStroke.parse("Mod+Shift+K"));
    assertEquals(new KeyStroke(KeyStroke.PRIMARY, '+'), KeyStroke.parse("Mod++"));
    assertEquals(
        new KeyStroke(KeyStroke.CONTROL, KeyStroke.F1 + 11), KeyStroke.parse("Control+F12"));
    assertNull(KeyStroke.parse("Mod+Nothing"));
    assertNull(KeyStroke.parse("F13"));
    assertNull(KeyStroke.parse(""));
  }

  @Test
  void onlyAPlainPrintableKeyOrSpaceTypes() {
    assertTrue(new KeyStroke(0, 'k').types());
    assertTrue(new KeyStroke(KeyStroke.SHIFT, 'k').types());
    assertTrue(new KeyStroke(0, KeyStroke.SPACE).types());
    assertFalse(new KeyStroke(KeyStroke.ALT, 'k').types());
    assertFalse(new KeyStroke(0, KeyStroke.F1).types());
  }

  @Test
  void overridesReplaceACommandsKeysAndKeepItsScope() {
    Keymap defaults = Keymap.defaults(false);
    KeyStroke k = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
    Keymap keymap =
        defaults.withOverrides(
            ShortcutOverrides.NONE
                .with(CommandId.FIND, List.of(k))
                .with(CommandId.COPY, List.of(new KeyStroke(KeyStroke.ALT, 'c')))
                .with(CommandId.SAVE, List.of()));
    assertEquals(k, keymap.accelerator(CommandId.FIND));
    assertEquals("Ctrl+Alt+K", keymap.keysText(CommandId.FIND));
    assertEquals(CommandId.COPY, keymap.editorCommand(new KeyStroke(KeyStroke.ALT, 'c')));
    assertNull(keymap.accelerator(CommandId.COPY));
    assertEquals("", keymap.keysText(CommandId.SAVE));
    assertNull(keymap.holder(new KeyStroke(KeyStroke.PRIMARY, 's'), CommandId.FIND));
  }

  @Test
  void compareCommandsHaveNoDefaultKeyAndTakeOneAsWindowCommands() {
    Keymap defaults = Keymap.defaults(false);
    assertEquals("", defaults.keysText(CommandId.COMPARE_DOCUMENTS));
    assertEquals("", defaults.keysText(CommandId.COMPARE_WITH_DISK));
    KeyStroke f7 = new KeyStroke(0, KeyStroke.F1 + 6);
    Keymap keymap =
        defaults.withOverrides(
            ShortcutOverrides.NONE.with(CommandId.COMPARE_WITH_DISK, List.of(f7)));
    assertEquals(f7, keymap.accelerator(CommandId.COMPARE_WITH_DISK));
  }

  @Test
  void aSharedKeyIsReportedInTheConflictsAndTheReference() {
    Keymap defaults = Keymap.defaults(false);
    assertTrue(defaults.conflicts().isEmpty());
    assertFalse(defaults.reference().contains("Conflicts"));
    KeyStroke save = new KeyStroke(KeyStroke.PRIMARY, 's');
    assertEquals(CommandId.SAVE, defaults.holder(save, CommandId.FIND));
    Keymap keymap =
        defaults.withOverrides(ShortcutOverrides.NONE.with(CommandId.FIND, List.of(save)));
    assertEquals(List.of("Ctrl+S: Save, Find"), keymap.conflicts());
    assertTrue(keymap.reference().contains("Conflicts\n  Ctrl+S: Save, Find\n"));
  }

  @Test
  void overridesParseAndRefuseWhatCannotBeBound() {
    List<String> refused = new ArrayList<String>();
    ShortcutOverrides overrides =
        ShortcutOverrides.parse(
            Map.of(
                "shortcut.FIND", "Mod+K Alt+K",
                "shortcut.SAVE", "",
                "shortcut.COPY", "k",
                "shortcut.CUT", "Mod+Nope",
                "shortcut.GONE", "Mod+K"),
            refused);
    assertEquals(2, overrides.strokes(CommandId.FIND).size());
    assertEquals(List.of(), overrides.strokes(CommandId.SAVE));
    assertNull(overrides.strokes(CommandId.COPY));
    assertEquals(2, refused.size());
    assertEquals("Mod+K Alt+K", overrides.keys().get("shortcut.FIND"));
    assertEquals(overrides, ShortcutOverrides.parse(overrides.keys(), new ArrayList<String>()));
  }

  /** The workspace browser handles plain Delete and F2 itself; a window binding would take them. */
  @Test
  void noDefaultWindowBindingTakesPlainDeleteOrF2() {
    KeyStroke delete = new KeyStroke(0, KeyStroke.DELETE);
    KeyStroke f2 = new KeyStroke(0, KeyStroke.F1 + 1);
    for (boolean mac : new boolean[] {false, true}) {
      for (Binding binding : Keymap.defaults(mac).bindings()) {
        if (binding.scope() == Binding.Scope.WINDOW) {
          assertFalse(binding.stroke().equals(delete), binding.toString());
          assertFalse(binding.stroke().equals(f2), binding.toString());
        }
      }
    }
  }

  /**
   * Exit is a window key off macOS, held by no other default on Linux or Windows; macOS leaves Quit
   * to the application menu. The macOS menu items of Find Next and Find Previous show Command+G.
   */
  @Test
  void exitAndMacFindKeys() {
    KeyStroke q = KeyStroke.of(KeyStroke.PRIMARY, 'q');
    for (boolean linux : new boolean[] {false, true}) {
      Keymap keymap = Keymap.defaults(false, linux);
      assertEquals(q, keymap.accelerator(CommandId.QUIT));
      assertNull(keymap.holder(q, CommandId.QUIT));
      assertEquals(List.of(), keymap.duplicates());
    }
    Keymap mac = Keymap.defaults(true);
    assertEquals("", mac.keysText(CommandId.QUIT));
    assertNull(mac.holder(q, CommandId.QUIT));
    assertEquals("Cmd+G", mac.menuKeyText(CommandId.FIND_NEXT));
    assertEquals("Cmd+Shift+G", mac.menuKeyText(CommandId.FIND_PREVIOUS));
    assertEquals("Cmd+G, F3", mac.keysText(CommandId.FIND_NEXT));
    assertEquals(CommandId.FIND_NEXT, mac.windowCommand(new KeyStroke(0, KeyStroke.F1 + 2)));
  }
}
