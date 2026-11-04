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
   * Returns the default bindings. {@link KeyStroke#PRIMARY} is Command on macOS and Ctrl elsewhere;
   * Ctrl+Y and the Insert and Delete clipboard keys are bound only off macOS.
   *
   * @param mac true for macOS
   * @return the keymap
   */
  public static Keymap defaults(boolean mac) {
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
    window(b, CommandId.ZOOM_IN, P, '=');
    window(b, CommandId.ZOOM_IN, P, KeyStroke.KEYPAD_ADD);
    window(b, CommandId.ZOOM_OUT, P, '-');
    window(b, CommandId.ZOOM_OUT, P, KeyStroke.KEYPAD_SUBTRACT);
    window(b, CommandId.ZOOM_RESET, P, '0');
    window(b, CommandId.NEXT_TAB, P, KeyStroke.PAGE_DOWN);
    window(b, CommandId.PREVIOUS_TAB, P, KeyStroke.PAGE_UP);
    window(b, CommandId.KEYBOARD_SHORTCUTS, 0, KeyStroke.F1);
    editor(b, CommandId.NEWLINE, 0, KeyStroke.ENTER);
    editor(b, CommandId.NEWLINE, S, KeyStroke.ENTER);
    editor(b, CommandId.DELETE_BACK, 0, KeyStroke.BACKSPACE);
    editor(b, CommandId.DELETE_BACK, S, KeyStroke.BACKSPACE);
    editor(b, CommandId.DELETE_FORWARD, 0, KeyStroke.DELETE);
    editor(b, CommandId.CANCEL, 0, KeyStroke.ESCAPE);
    return new Keymap(b, mac);
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
    StringBuilder text = new StringBuilder();
    for (CommandGroup group : CommandGroup.values()) {
      boolean header = false;
      for (CommandId command : CommandId.values()) {
        String keys = keysText(command);
        if (command.group() != group || keys.isEmpty()) {
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
