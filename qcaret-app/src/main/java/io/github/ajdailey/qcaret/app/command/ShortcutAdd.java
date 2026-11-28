package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shortcut editor's Add: the captured key appended to a command's keys, or the message that
 * says why nothing changes. Immutable.
 *
 * @param strokes the command's keys with the captured one added, or null when nothing changes
 * @param message the message to show, empty on success
 */
public record ShortcutAdd(List<KeyStroke> strokes, String message) {
  /**
   * Adds a captured key to a command.
   *
   * @param command the selected command, or null
   * @param captured the captured key, or null
   * @param current the keymap with the editor's overrides
   * @param mac true to name keys as macOS does
   * @return the outcome; {@link #strokes} null when the command or the key is missing, the key
   *     types, another command or a macro holds it, the command already has it, or the command has
   *     {@link ShortcutOverrides#MAX_PER_COMMAND} keys
   */
  public static ShortcutAdd of(CommandId command, KeyStroke captured, Keymap current, boolean mac) {
    if (command == null) {
      return refused(UiText.PREFERENCES_KEY_NO_COMMAND.text());
    }
    if (captured == null) {
      return refused(UiText.PREFERENCES_KEY_NO_KEY.text());
    }
    String keyText = captured.text(mac);
    if (captured.types()) {
      return refused(UiText.PREFERENCES_KEY_TYPES.format(keyText));
    }
    CommandId holder = current.holder(captured, command);
    if (holder != null) {
      return refused(UiText.PREFERENCES_KEY_TAKEN.format(keyText, holder.title()));
    }
    String macro = current.macros().holder(captured, null);
    if (macro != null) {
      return refused(UiText.PREFERENCES_KEY_TAKEN.format(keyText, UiText.MACRO_ROW.format(macro)));
    }
    List<KeyStroke> list = new ArrayList<KeyStroke>(current.strokes(command));
    if (list.contains(captured)) {
      return refused(UiText.PREFERENCES_KEY_ALREADY.format(keyText, command.title()));
    }
    if (list.size() >= ShortcutOverrides.MAX_PER_COMMAND) {
      return refused(
          UiText.PREFERENCES_KEY_FULL.format(command.title(), ShortcutOverrides.MAX_PER_COMMAND));
    }
    list.add(captured);
    return new ShortcutAdd(Collections.unmodifiableList(list), "");
  }

  private static ShortcutAdd refused(String message) {
    return new ShortcutAdd(null, message);
  }
}
