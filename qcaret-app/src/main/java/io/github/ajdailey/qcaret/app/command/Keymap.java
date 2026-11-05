package io.github.ajdailey.qcaret.app.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The one table of key bindings. Menu accelerators, the editor's key dispatch and Help > Keyboard
 * Shortcuts all read it. Only {@link Binding.Scope#WINDOW} bindings become menu accelerators; the
 * editor runs its {@link Binding.Scope#EDITOR} bindings itself, so a key such as {@code Ctrl+C}
 * never fires from another control. Immutable.
 */
public final class Keymap {
  private static final int P = KeyStroke.PRIMARY;
  private static final int S = KeyStroke.SHIFT;
  private static final int F2 = KeyStroke.F1 + 1;
  private static final int F3 = KeyStroke.F1 + 2;
  private static final int F6 = KeyStroke.F1 + 5;

  private final List<Binding> bindings;
  private final boolean mac;

  /**
   * Creates a keymap.
   *
   * @param bindings the bindings, in display order
   * @param mac true when keys are written with macOS names
   */
  public Keymap(List<Binding> bindings, boolean mac) {
    this.bindings = Collections.unmodifiableList(new ArrayList<Binding>(bindings));
    this.mac = mac;
  }

  /**
   * Returns the default bindings off Linux; see {@link #defaults(boolean, boolean)}.
   *
   * @param mac true for macOS
   * @return the keymap
   */
  public static Keymap defaults(boolean mac) {
    return defaults(mac, false);
  }

  /**
   * Returns the default bindings. {@link KeyStroke#PRIMARY} is Command on macOS and Ctrl elsewhere;
   * Ctrl+Y and the Insert and Delete clipboard keys are bound only off macOS. Upper and lower case
   * have no keys on Linux, where IBus takes Ctrl+Shift+U. On macOS, where Option+Shift with Left or
   * Right selects words, the block selection keys add Command, and Add Caret Above and Below take
   * Control+Shift.
   *
   * @param mac true for macOS
   * @param linux true for Linux
   * @return the keymap
   */
  public static Keymap defaults(boolean mac, boolean linux) {
    List<Binding> b = new ArrayList<Binding>();
    window(b, CommandId.NEW, P, 'n');
    window(b, CommandId.OPEN, P, 'o');
    window(b, CommandId.SAVE, P, 's');
    window(b, CommandId.SAVE_AS, P | S, 's');
    window(b, CommandId.CLOSE, P, 'w');
    window(b, CommandId.CLOSE_ALL, P | S, 'w');
    editor(b, CommandId.UNDO, P, 'z');
    editor(b, CommandId.REDO, P | S, 'z');
    if (!mac) {
      editor(b, CommandId.REDO, P, 'y');
    }
    editor(b, CommandId.CUT, P, 'x');
    editor(b, CommandId.COPY, P, 'c');
    editor(b, CommandId.PASTE, P, 'v');
    if (!mac) {
      editor(b, CommandId.CUT, S, KeyStroke.DELETE);
      editor(b, CommandId.COPY, P, KeyStroke.INSERT);
      editor(b, CommandId.PASTE, S, KeyStroke.INSERT);
    }
    editor(b, CommandId.SELECT_ALL, P, 'a');
    editing(b, mac, linux);
    productivity(b, mac);
    window(b, CommandId.ZOOM_IN, P, '=');
    window(b, CommandId.ZOOM_IN, P, KeyStroke.KEYPAD_ADD);
    window(b, CommandId.ZOOM_OUT, P, '-');
    window(b, CommandId.ZOOM_OUT, P, KeyStroke.KEYPAD_SUBTRACT);
    window(b, CommandId.ZOOM_RESET, P, '0');
    window(b, CommandId.NEXT_TAB, P, KeyStroke.PAGE_DOWN);
    window(b, CommandId.PREVIOUS_TAB, P, KeyStroke.PAGE_UP);
    window(b, CommandId.KEYBOARD_SHORTCUTS, 0, KeyStroke.F1);
    window(b, CommandId.FOCUS_OTHER_VIEW, 0, F6);
    window(b, CommandId.FIND, P, 'f');
    window(b, CommandId.FIND_NEXT, 0, F3);
    window(b, CommandId.FIND_PREVIOUS, S, F3);
    if (mac) {
      window(b, CommandId.FIND_NEXT, P, 'g');
      window(b, CommandId.FIND_PREVIOUS, P | S, 'g');
      window(b, CommandId.REPLACE, P | KeyStroke.ALT, 'f');
    } else {
      window(b, CommandId.REPLACE, P, 'h');
    }
    editor(b, CommandId.NEWLINE, 0, KeyStroke.ENTER);
    editor(b, CommandId.NEWLINE, S, KeyStroke.ENTER);
    editor(b, CommandId.DELETE_BACK, 0, KeyStroke.BACKSPACE);
    editor(b, CommandId.DELETE_BACK, S, KeyStroke.BACKSPACE);
    editor(b, CommandId.DELETE_FORWARD, 0, KeyStroke.DELETE);
    editor(b, CommandId.CANCEL, 0, KeyStroke.ESCAPE);
    editor(b, CommandId.FOLD, P | S, '[');
    editor(b, CommandId.UNFOLD, P | S, ']');
    editor(b, CommandId.FOLD_ALL, P | KeyStroke.ALT, '[');
    editor(b, CommandId.UNFOLD_ALL, P | KeyStroke.ALT, ']');
    return new Keymap(b, mac);
  }

  /** Adds the editing, selection and caret-move bindings. */
  private static void editing(List<Binding> b, boolean mac, boolean linux) {
    int a = KeyStroke.ALT;
    window(b, CommandId.SAVE_ALL, P | a, 's');
    window(b, CommandId.REOPEN_CLOSED, P | S, 't');
    window(b, CommandId.GO_TO_LINE, P, mac ? 'l' : 'g');
    editor(b, CommandId.DUPLICATE, P, 'd');
    editor(b, CommandId.DELETE_LINE, P | S, 'l');
    editor(b, CommandId.MOVE_LINES_UP, a, KeyStroke.UP);
    editor(b, CommandId.MOVE_LINES_DOWN, a, KeyStroke.DOWN);
    editor(b, CommandId.JOIN_LINES, P, 'j');
    if (!linux) {
      editor(b, CommandId.UPPER_CASE, P | S, 'u');
      editor(b, CommandId.LOWER_CASE, P, 'u');
    }
    editor(b, CommandId.INDENT, P, ']');
    editor(b, CommandId.OUTDENT, P, '[');
    editor(b, CommandId.OUTDENT, S, KeyStroke.TAB);
    editor(b, CommandId.INSERT_TAB, 0, KeyStroke.TAB);
    int addCaret = (mac ? KeyStroke.CONTROL : P) | S;
    editor(b, CommandId.ADD_CARET_ABOVE, addCaret, KeyStroke.UP);
    editor(b, CommandId.ADD_CARET_BELOW, addCaret, KeyStroke.DOWN);
    editor(b, CommandId.ADD_NEXT_OCCURRENCE, P, 'e');
    editor(b, CommandId.TOGGLE_LINE_COMMENT, P, '/');
    editor(b, CommandId.TOGGLE_BLOCK_COMMENT, P | S, '/');
    int word = mac ? a : P;
    int block = mac ? P | a | S : a | S;
    for (int shift = 0; shift <= S; shift += S) {
      boolean select = shift != 0;
      editor(b, select ? CommandId.SELECT_LEFT : CommandId.CARET_LEFT, shift, KeyStroke.LEFT);
      editor(b, select ? CommandId.SELECT_RIGHT : CommandId.CARET_RIGHT, shift, KeyStroke.RIGHT);
      editor(b, select ? CommandId.SELECT_UP : CommandId.CARET_UP, shift, KeyStroke.UP);
      editor(b, select ? CommandId.SELECT_DOWN : CommandId.CARET_DOWN, shift, KeyStroke.DOWN);
      editor(
          b,
          select ? CommandId.SELECT_WORD_LEFT : CommandId.WORD_LEFT,
          word | shift,
          KeyStroke.LEFT);
      editor(
          b,
          select ? CommandId.SELECT_WORD_RIGHT : CommandId.WORD_RIGHT,
          word | shift,
          KeyStroke.RIGHT);
      CommandId start = select ? CommandId.SELECT_LINE_START : CommandId.LINE_START;
      CommandId end = select ? CommandId.SELECT_LINE_END : CommandId.LINE_END;
      editor(b, start, shift, KeyStroke.HOME);
      editor(b, end, shift, KeyStroke.END);
      if (mac) {
        editor(b, start, P | shift, KeyStroke.LEFT);
        editor(b, end, P | shift, KeyStroke.RIGHT);
      }
      editor(b, select ? CommandId.SELECT_PAGE_UP : CommandId.PAGE_UP, shift, KeyStroke.PAGE_UP);
      editor(
          b, select ? CommandId.SELECT_PAGE_DOWN : CommandId.PAGE_DOWN, shift, KeyStroke.PAGE_DOWN);
      CommandId top = select ? CommandId.SELECT_DOCUMENT_START : CommandId.DOCUMENT_START;
      CommandId bottom = select ? CommandId.SELECT_DOCUMENT_END : CommandId.DOCUMENT_END;
      editor(b, top, P | shift, KeyStroke.HOME);
      editor(b, bottom, P | shift, KeyStroke.END);
      if (mac) {
        editor(b, top, P | shift, KeyStroke.UP);
        editor(b, bottom, P | shift, KeyStroke.DOWN);
      }
    }
    editor(b, CommandId.BLOCK_LEFT, block, KeyStroke.LEFT);
    editor(b, CommandId.BLOCK_RIGHT, block, KeyStroke.RIGHT);
    editor(b, CommandId.BLOCK_UP, block, KeyStroke.UP);
    editor(b, CommandId.BLOCK_DOWN, block, KeyStroke.DOWN);
  }

  /**
   * Adds the bookmark, bracket, completion and command palette bindings. Word completion is
   * Ctrl+Space, or Option+Esc on macOS, where Ctrl+Space switches the input source.
   */
  private static void productivity(List<Binding> b, boolean mac) {
    editor(b, CommandId.TOGGLE_BOOKMARK, P, F2);
    editor(b, CommandId.NEXT_BOOKMARK, 0, F2);
    editor(b, CommandId.PREVIOUS_BOOKMARK, S, F2);
    editor(b, CommandId.GO_TO_MATCHING_BRACKET, P, 'b');
    editor(b, CommandId.SELECT_TO_MATCHING_BRACKET, P | S, 'b');
    if (mac) {
      editor(b, CommandId.COMPLETE_WORD, KeyStroke.ALT, KeyStroke.ESCAPE);
    } else {
      editor(b, CommandId.COMPLETE_WORD, P, KeyStroke.SPACE);
    }
    window(b, CommandId.COMMAND_PALETTE, P | S, 'p');
  }

  private static void window(List<Binding> b, CommandId command, int modifiers, char c) {
    b.add(new Binding(command, KeyStroke.of(modifiers, c), Binding.Scope.WINDOW));
  }

  private static void window(List<Binding> b, CommandId command, int modifiers, int key) {
    b.add(new Binding(command, new KeyStroke(modifiers, key), Binding.Scope.WINDOW));
  }

  private static void editor(List<Binding> b, CommandId command, int modifiers, char c) {
    b.add(new Binding(command, KeyStroke.of(modifiers, c), Binding.Scope.EDITOR));
  }

  private static void editor(List<Binding> b, CommandId command, int modifiers, int key) {
    b.add(new Binding(command, new KeyStroke(modifiers, key), Binding.Scope.EDITOR));
  }

  /**
   * Reports whether keys are written with macOS names.
   *
   * @return true for macOS
   */
  public boolean mac() {
    return mac;
  }

  /**
   * Returns every binding.
   *
   * @return the bindings, in display order
   */
  public List<Binding> bindings() {
    return bindings;
  }

  /**
   * Returns the stroke a menu item sets as its accelerator: the command's first window binding.
   *
   * @param command the command
   * @return the stroke, or null when the command has no window binding
   */
  public KeyStroke accelerator(CommandId command) {
    for (Binding binding : bindings) {
      if (binding.command() == command && binding.scope() == Binding.Scope.WINDOW) {
        return binding.stroke();
      }
    }
    return null;
  }

  /**
   * Returns a command's keys as the platform writes them, joined with {@code ", "}.
   *
   * @param command the command
   * @return the keys, empty when the command has none
   */
  public String keysText(CommandId command) {
    StringBuilder text = new StringBuilder();
    for (Binding binding : bindings) {
      if (binding.command() == command) {
        if (text.length() > 0) {
          text.append(", ");
        }
        text.append(binding.text(mac));
      }
    }
    return text.toString();
  }

  /**
   * Returns the shortcut reference as plain text: one line per command with keys, grouped by menu,
   * the editor's keys last.
   *
   * @return the text
   */
  public String reference() {
    return reference("");
  }

  /**
   * Returns the shortcut reference's rows whose group, command or keys contain a filter, ignoring
   * case, as {@link #reference()} writes them.
   *
   * @param filter the text sought; empty keeps every row
   * @return the text
   */
  public String reference(String filter) {
    String needle = filter.toLowerCase(Locale.ROOT);
    StringBuilder text = new StringBuilder();
    for (CommandGroup group : CommandGroup.values()) {
      boolean header = false;
      for (CommandId command : CommandId.values()) {
        String keys = keysText(command);
        if (command.group() != group || keys.isEmpty()) {
          continue;
        }
        String row = group.title() + " " + command.title() + " " + keys;
        if (!row.toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        if (!header) {
          text.append(group.title()).append('\n');
          header = true;
        }
        text.append("  ").append(command.title()).append('\t').append(keys).append('\n');
      }
    }
    return text.toString();
  }

  /**
   * Returns the command an editor runs for a stroke.
   *
   * @param stroke the stroke
   * @return the command, or null when the stroke has no editor binding
   */
  public CommandId editorCommand(KeyStroke stroke) {
    for (Binding binding : bindings) {
      if (binding.scope() == Binding.Scope.EDITOR && binding.stroke().equals(stroke)) {
        return binding.command();
      }
    }
    return null;
  }

  /**
   * Returns the command a window stroke runs when it is not the command's menu accelerator, such as
   * a second window binding.
   *
   * @param stroke the stroke
   * @return the command, or null when the stroke is an accelerator or unbound in the window
   */
  public CommandId windowCommand(KeyStroke stroke) {
    for (Binding binding : bindings) {
      if (binding.scope() == Binding.Scope.WINDOW && binding.stroke().equals(stroke)) {
        return stroke.equals(accelerator(binding.command())) ? null : binding.command();
      }
    }
    return null;
  }

  /**
   * Lists strokes bound more than once: twice in one scope, or in the window and an editor, where
   * the window binding also reaches the editor.
   *
   * @return each duplicated stroke as the platform writes it, lower case, empty when none
   */
  public List<String> duplicates() {
    List<String> found = new ArrayList<String>();
    for (int i = 0; i < bindings.size(); i++) {
      for (int j = i + 1; j < bindings.size(); j++) {
        if (bindings.get(i).stroke().equals(bindings.get(j).stroke())) {
          found.add(bindings.get(i).text(mac).toLowerCase(Locale.ROOT));
        }
      }
    }
    return found;
  }
}
