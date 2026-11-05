package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;

/** The Preferences dialog's controller as its view calls it. Called on the UI thread. */
public interface PreferencesInput {
  /**
   * Returns the running settings as fields.
   *
   * @return the form
   */
  PreferencesForm form();

  /**
   * Returns the default settings as fields; nothing changes until {@link #apply}.
   *
   * @return the form
   */
  PreferencesForm defaults();

  /**
   * Returns the keymap a set of overrides makes.
   *
   * @param overrides the overrides
   * @return the default keys with the overrides in place
   */
  Keymap keymap(ShortcutOverrides overrides);

  /**
   * Checks the fields, then applies them to the running windows and saves them on a worker. A
   * refused form changes nothing.
   *
   * @param form the fields
   * @return null when applied, else the first invalid field
   */
  PreferencesForm.Problem apply(PreferencesForm form);
}
