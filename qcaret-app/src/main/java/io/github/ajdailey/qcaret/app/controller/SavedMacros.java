package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.MacroKeys;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The saved macros of a {@link WindowSet}: the last {@link MacroStore} listing and the {@link
 * MacroKeys} built from its readable entries. {@link #reload} lists the folder again and runs the
 * change listener when the readable macros or their keys changed, so every window's keymap and
 * Macros menu follow. Confined to the UI thread.
 */
final class SavedMacros {
  private final MacroStore store;
  private final Runnable changed;
  private List<MacroStore.Entry> entries = List.of();
  private MacroKeys keys = MacroKeys.NONE;

  /**
   * Creates the list, empty until the first {@link #reload}.
   *
   * @param store the folder, or null for a stand-in that lists nothing
   * @param changed runs when the readable macros or their keys change
   */
  SavedMacros(MacroStore store, Runnable changed) {
    this.store = store;
    this.changed = changed;
  }

  /**
   * Returns the folder.
   *
   * @return the store
   */
  MacroStore store() {
    return store;
  }

  /**
   * Lists the folder again.
   *
   * @param then runs on the UI thread after the list and its change listener updated, or null
   */
  void reload(final Runnable then) {
    if (store == null) {
      return;
    }
    store.list(
        new Consumer<List<MacroStore.Entry>>() {
          @Override
          public void accept(List<MacroStore.Entry> listed) {
            entries = listed;
            List<MacroKeys.Entry> readable = new ArrayList<MacroKeys.Entry>();
            for (MacroStore.Entry entry : listed) {
              if (entry.macro() != null) {
                readable.add(new MacroKeys.Entry(entry.name(), entry.macro().key()));
              }
            }
            MacroKeys next = new MacroKeys(readable);
            if (!next.equals(keys)) {
              keys = next;
              changed.run();
            }
            if (then != null) {
              then.run();
            }
          }
        });
  }

  /**
   * Returns the last listing, unreadable files included.
   *
   * @return the entries by name ignoring case
   */
  List<MacroStore.Entry> entries() {
    return entries;
  }

  /**
   * Returns the readable macros and their keys.
   *
   * @return the table
   */
  MacroKeys keys() {
    return keys;
  }

  /**
   * Returns a readable saved macro.
   *
   * @param name the name, matched exactly
   * @return the macro, or null
   */
  Macro macro(String name) {
    for (MacroStore.Entry entry : entries) {
      if (entry.name().equals(name)) {
        return entry.macro();
      }
    }
    return null;
  }

  /**
   * Returns every listed name, unreadable files included, for the name checks.
   *
   * @param except a name left out, or null
   * @return the names
   */
  List<String> names(String except) {
    List<String> names = new ArrayList<String>();
    for (MacroStore.Entry entry : entries) {
      if (!entry.name().equals(except)) {
        names.add(entry.name());
      }
    }
    return names;
  }
}
