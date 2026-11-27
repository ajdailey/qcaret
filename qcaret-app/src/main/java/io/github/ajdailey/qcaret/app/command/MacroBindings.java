package io.github.ajdailey.qcaret.app.command;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A keymap's saved macro keys ({@link Keymap#macros}): which macro a window key runs, each macro's
 * menu accelerator, the checks a new macro key passes, and the macros' rows in the shortcut
 * reference and conflicts. A binding holding a macro's key keeps it, an earlier macro keeps a key a
 * later one shares, and a key written by hand that types or holds Ctrl and Alt off macOS runs
 * nothing. Immutable.
 */
public final class MacroBindings {
  private final Keymap keymap;
  private final MacroKeys keys;

  MacroBindings(Keymap keymap, MacroKeys keys) {
    this.keymap = keymap;
    this.keys = keys;
  }

  /**
   * Returns the saved macros.
   *
   * @return the table, {@link MacroKeys#NONE} by default
   */
  public MacroKeys keys() {
    return keys;
  }

  /**
   * Returns the saved macro a window key runs.
   *
   * @param stroke the key
   * @return the macro's name, or null when no macro has the key, a binding holds it, or the key
   *     types or holds Ctrl and Alt off macOS
   */
  public String macro(KeyStroke stroke) {
    if (stroke.types() || altGr(stroke) || keymap.holder(stroke, null) != null) {
      return null;
    }
    return keys.macro(stroke);
  }

  /**
   * Returns the key a saved macro's menu item sets as its accelerator.
   *
   * @param name the macro's name
   * @return its key, or null when it has none, a binding or an earlier macro holds it, or its form
   *     runs nothing
   */
  public KeyStroke accelerator(String name) {
    KeyStroke key = keys.key(name);
    return key == null || !name.equals(macro(key)) ? null : key;
  }

  /**
   * Returns what {@link Keymap#windowCommand} runs for a key, as a target. A macro key is its menu
   * item's accelerator, so the target is a command or null.
   *
   * @param stroke the key
   * @return the target, or null
   */
  public KeyTarget windowTarget(KeyStroke stroke) {
    return KeyTarget.of(keymap.windowCommand(stroke));
  }

  /**
   * Returns what a window key runs, a menu accelerator and a saved macro's key included.
   *
   * @param stroke the key
   * @return the target, or null when the key is unbound in the window
   */
  public KeyTarget windowBindingTarget(KeyStroke stroke) {
    CommandId command = keymap.windowBinding(stroke);
    return command != null ? KeyTarget.of(command) : KeyTarget.macro(macro(stroke));
  }

  /**
   * Returns the saved macro holding a key, apart from a given one.
   *
   * @param stroke the key
   * @param except the macro left out, or null
   * @return the first other macro with the key, or null
   */
  public String holder(KeyStroke stroke, String except) {
    for (MacroKeys.Entry entry : keys.entries()) {
      if (stroke.equals(entry.key()) && !entry.name().equals(except)) {
        return entry.name();
      }
    }
    return null;
  }

  /**
   * Checks a key for a saved macro: it must not type, must not hold Ctrl and Alt off macOS, where
   * Windows delivers AltGr as Ctrl+Alt, and no binding or other macro may hold it.
   *
   * @param stroke the key
   * @param name the macro, whose own key does not count
   * @return why the key is refused, or null
   */
  public String keyProblem(KeyStroke stroke, String name) {
    String text = keymap.keyText(stroke);
    if (stroke.types()) {
      return UiText.PREFERENCES_KEY_TYPES.format(text);
    }
    if (altGr(stroke)) {
      return UiText.MACRO_KEY_ALTGR.format(text);
    }
    CommandId command = keymap.holder(stroke, null);
    if (command != null) {
      return UiText.PREFERENCES_KEY_TAKEN.format(text, command.title());
    }
    String other = holder(stroke, name);
    return other == null
        ? null
        : UiText.PREFERENCES_KEY_TAKEN.format(text, UiText.MACRO_ROW.format(other));
  }

  /**
   * Returns a saved macro's key as the platform writes it.
   *
   * @param name the macro's name
   * @return the key, empty when it has none
   */
  public String keyText(String name) {
    KeyStroke key = keys.key(name);
    return key == null ? "" : keymap.keyText(key);
  }

  /**
   * Lists the macro keys a binding or an earlier macro also holds, as {@link Keymap#conflicts}
   * writes them.
   *
   * @return one line per conflict, empty when none
   */
  List<String> conflicts() {
    List<String> found = new ArrayList<String>();
    for (MacroKeys.Entry entry : keys.entries()) {
      KeyStroke key = entry.key();
      if (key == null) {
        continue;
      }
      CommandId command = keymap.holder(key, null);
      String other = command != null ? command.title() : keys.macro(key);
      if (command != null || !other.equals(entry.name())) {
        found.add(
            UiText.SHORTCUTS_CONFLICT_LINE.format(
                keymap.keyText(key),
                command != null ? other : UiText.MACRO_ROW.format(other),
                UiText.MACRO_ROW.format(entry.name())));
      }
    }
    return found;
  }

  /**
   * Appends the reference rows of the saved macros with keys that match a filter, under the Macros
   * header unless one is written.
   */
  void referenceRows(StringBuilder text, String needle, boolean header) {
    boolean written = header;
    String group = CommandGroup.MACROS.title();
    for (MacroKeys.Entry entry : keys.entries()) {
      if (entry.key() == null) {
        continue;
      }
      String title = UiText.MACRO_ROW.format(entry.name());
      String keyText = keymap.keyText(entry.key());
      String row = group + " " + title + " " + keyText;
      if (!row.toLowerCase(Locale.ROOT).contains(needle)) {
        continue;
      }
      if (!written) {
        text.append(group).append('\n');
        written = true;
      }
      text.append("  ").append(title).append('\t').append(keyText).append('\n');
    }
  }

  private boolean altGr(KeyStroke stroke) {
    int ctrlAlt = KeyStroke.PRIMARY | KeyStroke.ALT;
    return !keymap.mac() && (stroke.modifiers() & ctrlAlt) == ctrlAlt;
  }
}
