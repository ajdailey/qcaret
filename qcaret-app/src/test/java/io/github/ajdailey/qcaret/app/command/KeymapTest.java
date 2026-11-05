package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
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
                + "  Cancel Save, Extra Carets or Find Bar\tEsc\n"),
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

  /** Line transforms stay menu only, as the reference keymap proposes. */
  private static final Set<CommandId> MENU_ONLY =
      EnumSet.of(
          CommandId.SORT_LINES_ASCENDING,
          CommandId.SORT_LINES_DESCENDING,
          CommandId.SORT_LINES_IGNORE_CASE,
          CommandId.REMOVE_DUPLICATE_LINES,
          CommandId.TRIM_TRAILING_WHITESPACE);

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
   * Edit Bookmark Comment takes Mod+Shift+F2 beside the other bookmark keys: no default of GNOME,
   * KDE, Windows or macOS uses it, unlike Alt+F2 (GNOME and KDE run dialogs) and Ctrl+F2 (macOS
   * menu bar focus).
   */
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
}
