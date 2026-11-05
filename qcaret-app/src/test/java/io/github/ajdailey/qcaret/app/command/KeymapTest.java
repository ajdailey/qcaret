package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
                + "  Cancel Save and Extra Carets\tEsc\n"),
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

  @Test
  void everyEditingCommandHasAKey() {
    Keymap keymap = Keymap.defaults(true);
    for (CommandId command : CommandId.values()) {
      boolean editing =
          command.group() == CommandGroup.EDIT || command.group() == CommandGroup.EDITOR;
      if (editing && !command.check()) {
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
}
