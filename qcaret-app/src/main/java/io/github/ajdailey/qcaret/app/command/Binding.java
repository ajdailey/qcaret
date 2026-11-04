package io.github.ajdailey.qcaret.app.command;

/**
 * One key binding. QCaret has no multi-stroke chords.
 *
 * @param command the command run
 * @param stroke the key
 * @param scope where the binding applies
 */
public record Binding(CommandId command, KeyStroke stroke, Scope scope) {
  /** Where a binding applies. */
  public enum Scope {
    /** Anywhere in the main window; the command's first one is the menu item's accelerator. */
    WINDOW,
    /** Only while an editor has focus; dispatched by the editor, so a text field never sees it. */
    EDITOR
  }

  /** Checks the components. */
  public Binding {
    if (command == null || stroke == null || scope == null) {
      throw new IllegalArgumentException("command, stroke and scope are required");
    }
  }

  /**
   * Returns the key as the platform writes it.
   *
   * @param mac true for macOS names
   * @return for example {@code Ctrl+Shift+S}
   */
  public String text(boolean mac) {
    return stroke.text(mac);
  }
}
