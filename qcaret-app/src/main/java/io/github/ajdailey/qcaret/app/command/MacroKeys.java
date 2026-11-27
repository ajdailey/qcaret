package io.github.ajdailey.qcaret.app.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The saved macros the Macros menu lists, in menu order, each with its key or none. A {@link
 * Keymap} carries one ({@link Keymap#withMacroKeys}); a macro key is a window key and its menu
 * item's accelerator. Immutable.
 */
public final class MacroKeys {
  /** No saved macros. */
  public static final MacroKeys NONE = new MacroKeys(List.of());

  /**
   * One saved macro.
   *
   * @param name the macro's name
   * @param key its key, or null
   */
  public record Entry(String name, KeyStroke key) {
    /** Checks the name. */
    public Entry {
      if (name == null) {
        throw new IllegalArgumentException("name is required");
      }
    }
  }

  private final List<Entry> entries;

  /**
   * Creates the table.
   *
   * @param entries the macros in menu order
   */
  public MacroKeys(List<Entry> entries) {
    this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
  }

  /**
   * Returns the macros.
   *
   * @return the entries in menu order
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Returns a macro's key.
   *
   * @param name the macro's name, matched exactly
   * @return the key, or null when the macro has none or is not listed
   */
  public KeyStroke key(String name) {
    for (Entry entry : entries) {
      if (entry.name().equals(name)) {
        return entry.key();
      }
    }
    return null;
  }

  /**
   * Returns the macro a key runs.
   *
   * @param stroke the key
   * @return the first macro with the key, or null
   */
  public String macro(KeyStroke stroke) {
    for (Entry entry : entries) {
      if (stroke.equals(entry.key())) {
        return entry.name();
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MacroKeys keys && keys.entries.equals(entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }
}
