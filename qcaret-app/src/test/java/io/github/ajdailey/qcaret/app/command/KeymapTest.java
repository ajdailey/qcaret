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
    assertTrue(
        reference.endsWith(
            "Editor\n  New Line\tEnter, Shift+Enter\n"
                + "  Delete Previous Char\tBackspace, Shift+Backspace\n"
                + "  Delete Next Char\tDelete\n"
                + "  Cancel Save and Extra Carets\tEsc\n"),
        reference);
  }

  @Test
  void everyEditingCommandHasAKey() {
    Keymap keymap = Keymap.defaults(true);
    for (CommandId command : CommandId.values()) {
      if (command.group() == CommandGroup.EDIT || command.group() == CommandGroup.EDITOR) {
        assertFalse(keymap.keysText(command).isEmpty(), command.name());
      }
    }
  }
}
