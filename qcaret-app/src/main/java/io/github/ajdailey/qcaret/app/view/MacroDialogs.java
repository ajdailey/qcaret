package io.github.ajdailey.qcaret.app.view;

import java.util.function.Consumer;
import java.util.function.Function;

/** The window's Macros menu dialogs and its status-bar macro field. UI thread. */
public interface MacroDialogs {
  /**
   * Shows the status bar's macro field: the recording marker or a run's progress.
   *
   * @param text the text; empty hides the field
   */
  void showMacroStatus(String text);

  /**
   * Asks for Macros > Run Multiple Times' macro and count; modal. OK is enabled while the check
   * finds no problem, whose text the dialog shows; it passes the fields to the answer and closes.
   * Cancel and Esc close without one.
   *
   * @param initial the fields' first values
   * @param problem returns why the fields are refused, or null
   * @param answer receives the fields
   */
  void askRunMacro(
      RunMacroForm initial, Function<RunMacroForm, String> problem, Consumer<RunMacroForm> answer);

  /**
   * Asks for Macros > Save Recorded Macro's name and key; modal, with the same check, answer and
   * cancel as {@link #askRunMacro}.
   *
   * @param initial the fields' first values
   * @param problem returns why the fields are refused, or null
   * @param answer receives the fields
   */
  void askSaveMacro(
      SaveMacroForm initial,
      Function<SaveMacroForm, String> problem,
      Consumer<SaveMacroForm> answer);

  /**
   * Opens Macros > Manage Macros, modal, listing {@link MacroManagerInput#rows}.
   *
   * @param input the dialog's controller
   * @return the dialog, which shows the rows again on {@link MacroManagerDisplay#rowsChanged}
   */
  MacroManagerDisplay showManageMacros(MacroManagerInput input);
}
