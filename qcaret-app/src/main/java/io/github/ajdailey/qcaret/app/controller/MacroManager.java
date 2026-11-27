package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
import io.github.ajdailey.qcaret.app.view.MacroManagerDisplay;
import io.github.ajdailey.qcaret.app.view.MacroManagerInput;
import java.util.ArrayList;
import java.util.List;

/**
 * Manage Macros' controller over the {@link SavedMacros} listing. A rename, delete or key change
 * writes the folder through {@link MacroStore}, lists it again and shows the rows again. Setting a
 * key writes the macro's file from its parsed steps, so the file's comment lines are dropped.
 * Confined to the UI thread.
 */
final class MacroManager implements MacroManagerInput {
  private final MacroController macros;
  private final SavedMacros saved;
  private MacroManagerDisplay display;

  MacroManager(MacroController macros, SavedMacros saved) {
    this.macros = macros;
    this.saved = saved;
  }

  /** Sets the dialog the folder changes refresh. */
  void setDisplay(MacroManagerDisplay value) {
    display = value;
  }

  @Override
  public List<Row> rows() {
    List<Row> rows = new ArrayList<Row>();
    for (MacroStore.Entry entry : saved.entries()) {
      rows.add(
          new Row(
              entry.name(),
              entry.macro() == null ? null : entry.macro().key(),
              entry.problem() == null ? "" : entry.problem()));
    }
    return rows;
  }

  @Override
  public void run(String name) {
    Macro macro = saved.macro(name);
    if (macro != null) {
      macros.play(macro);
    }
  }

  @Override
  public String rename(String name, String newName) {
    if (name.equals(newName)) {
      return null;
    }
    String problem = MacroController.nameProblem(newName, saved.names(name));
    if (problem == null) {
      saved.store().rename(name, newName, macros.changed(null, display));
    }
    return problem;
  }

  @Override
  public void delete(String name) {
    saved.store().delete(name, macros.changed(null, display));
  }

  @Override
  public String setKey(String name, KeyStroke key) {
    Macro macro = saved.macro(name);
    if (macro == null) {
      return UiText.MACRO_UNREADABLE_KEY.text();
    }
    String problem = key == null ? null : macros.keyProblem(key, name);
    if (problem == null) {
      macros.save(new Macro(name, key, macro.steps()), display);
    }
    return problem;
  }
}
