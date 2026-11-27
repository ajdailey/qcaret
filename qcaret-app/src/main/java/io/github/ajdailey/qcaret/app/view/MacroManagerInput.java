package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import java.util.List;

/**
 * Manage Macros' controller as its dialog calls it. A rename, delete or key change writes the
 * macros folder on a worker; the dialog's {@link MacroManagerDisplay#rowsChanged} follows once the
 * folder is listed again. UI thread.
 */
public interface MacroManagerInput {
  /**
   * One file of the macros folder.
   *
   * @param name the macro's name
   * @param key its key, or null
   * @param problem why the file is unreadable, or empty for a macro that runs
   */
  record Row(String name, KeyStroke key, String problem) {}

  /**
   * Returns the rows, by name ignoring case.
   *
   * @return the macros folder's files
   */
  List<Row> rows();

  /**
   * Runs a macro once in the window's selected editor; the dialog closes first.
   *
   * @param name the macro's name
   */
  void run(String name);

  /**
   * Renames a macro.
   *
   * @param name the macro's name
   * @param newName the new name
   * @return why the name is refused, or null when the rename started
   */
  String rename(String name, String newName);

  /**
   * Deletes a macro's file; the dialog asks first.
   *
   * @param name the macro's name
   */
  void delete(String name);

  /**
   * Sets or clears a macro's key.
   *
   * @param name the macro's name
   * @param key the key, or null to clear it
   * @return why the key is refused, or null when the change started
   */
  String setKey(String name, KeyStroke key);
}
