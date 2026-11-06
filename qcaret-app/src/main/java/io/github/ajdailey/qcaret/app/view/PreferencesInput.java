package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import java.util.function.Consumer;

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

  /**
   * Lists the theme files and the monospace font families on a worker.
   *
   * @param done receives them on the UI thread; not called when the workers are closed
   */
  void loadChoices(Consumer<AppearanceChoices> done);

  /**
   * Clears every saved search history and HyperSearch's last run at once, independently of {@link
   * #apply}: the file is read on a worker first, so entries another process saved go too, and
   * Cancel does not bring them back. A failed save shows a notice; another call retries it.
   */
  void clearHistory();
}
