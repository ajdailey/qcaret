package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each outcome of the shortcut editor's Add: the key added, or a message and no change. */
class ShortcutAddTest {
  private static final KeyStroke FREE = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
  private static final Keymap KEYMAP = Keymap.defaults(false);

  @Test
  void aFreeKeyIsAddedAfterTheCommandsKeys() {
    ShortcutAdd add = ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, FREE, KEYMAP, false);
    List<KeyStroke> expected =
        new ArrayList<KeyStroke>(KEYMAP.strokes(CommandId.COMPARE_DOCUMENTS));
    expected.add(FREE);
    assertEquals(expected, add.strokes());
    assertEquals("", add.message());
  }

  @Test
  void noCommandAndNoKeyEachShowAMessage() {
    assertRefused(
        UiText.PREFERENCES_KEY_NO_COMMAND.text(), ShortcutAdd.of(null, FREE, KEYMAP, false));
    assertRefused(
        UiText.PREFERENCES_KEY_NO_KEY.text(),
        ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, null, KEYMAP, false));
  }

  @Test
  void aKeyTheCommandHasShowsAMessage() {
    KeyStroke save = KEYMAP.strokes(CommandId.SAVE).get(0);
    assertRefused(
        UiText.PREFERENCES_KEY_ALREADY.format(save.text(false), CommandId.SAVE.title()),
        ShortcutAdd.of(CommandId.SAVE, save, KEYMAP, false));
  }

  @Test
  void aTypingKeyAnotherCommandsKeyAndAMacrosKeyEachShowAMessage() {
    KeyStroke typing = new KeyStroke(0, 'k');
    assertRefused(
        UiText.PREFERENCES_KEY_TYPES.format(typing.text(false)),
        ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, typing, KEYMAP, false));
    KeyStroke save = KEYMAP.strokes(CommandId.SAVE).get(0);
    assertRefused(
        UiText.PREFERENCES_KEY_TAKEN.format(save.text(false), CommandId.SAVE.title()),
        ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, save, KEYMAP, false));
    Keymap macros =
        KEYMAP.withMacroKeys(new MacroKeys(List.of(new MacroKeys.Entry("Mock macro", FREE))));
    assertRefused(
        UiText.PREFERENCES_KEY_TAKEN.format(
            FREE.text(false), UiText.MACRO_ROW.format("Mock macro")),
        ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, FREE, macros, false));
  }

  @Test
  void aCommandWithTheMostKeysShowsAMessage() {
    List<KeyStroke> full = new ArrayList<KeyStroke>();
    for (int i = 0; i < ShortcutOverrides.MAX_PER_COMMAND; i++) {
      full.add(
          new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT | KeyStroke.SHIFT, (char) ('a' + i)));
    }
    Keymap keymap =
        KEYMAP.withOverrides(ShortcutOverrides.NONE.with(CommandId.COMPARE_DOCUMENTS, full));
    assertRefused(
        UiText.PREFERENCES_KEY_FULL.format(
            CommandId.COMPARE_DOCUMENTS.title(), ShortcutOverrides.MAX_PER_COMMAND),
        ShortcutAdd.of(CommandId.COMPARE_DOCUMENTS, FREE, keymap, false));
  }

  private static void assertRefused(String message, ShortcutAdd add) {
    assertNull(add.strokes());
    assertEquals(message, add.message());
  }
}
