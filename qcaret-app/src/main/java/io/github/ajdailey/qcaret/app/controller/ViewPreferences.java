package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;

/** Maps the Preferences dialog's View tab to and from {@link UiState}. */
final class ViewPreferences {
  private ViewPreferences() {}

  /**
   * Returns the View tab's boxes for remembered UI state.
   *
   * @param s the state
   * @return the boxes
   */
  static PreferencesForm.ViewChecks checks(UiState s) {
    ViewDefaults v = s.view();
    return new PreferencesForm.ViewChecks(
        v.wordWrap(),
        v.whitespace(),
        v.lineEndings(),
        v.overview(),
        v.lineNumbers(),
        v.workspaceBrowser(),
        s.toolbar(),
        s.rightMargin(),
        s.indentGuides(),
        s.lineChanges());
  }

  /**
   * Returns remembered UI state with the boxes the user changed; a box left as shown keeps the
   * state's value, so a View menu toggle made while the dialog shows stays.
   *
   * @param s the state
   * @param shown the boxes as the dialog showed them, or as the last Apply left them
   * @param c the boxes now
   * @return the copy
   */
  static UiState state(UiState s, PreferencesForm.ViewChecks shown, PreferencesForm.ViewChecks c) {
    ViewDefaults v = s.view();
    return s.withView(
            new ViewDefaults(
                pick(v.wordWrap(), shown.wordWrap(), c.wordWrap()),
                pick(v.whitespace(), shown.whitespace(), c.whitespace()),
                pick(v.lineEndings(), shown.lineEndings(), c.lineEndings()),
                pick(v.overview(), shown.overview(), c.overview()),
                pick(v.lineNumbers(), shown.lineNumbers(), c.lineNumbers()),
                pick(v.workspaceBrowser(), shown.workspaceBrowser(), c.workspaceBrowser())))
        .withToolbar(pick(s.toolbar(), shown.toolbar(), c.toolbar()))
        .withRightMargin(pick(s.rightMargin(), shown.rightMargin(), c.rightMargin()))
        .withIndentGuides(pick(s.indentGuides(), shown.indentGuides(), c.indentGuides()))
        .withLineChanges(pick(s.lineChanges(), shown.lineChanges(), c.lineChanges()));
  }

  /** Returns the box when the user changed it, else the current value. */
  private static boolean pick(boolean current, boolean shown, boolean box) {
    return box != shown ? box : current;
  }
}
