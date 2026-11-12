package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;

/**
 * The settings in {@code qcaret.conf}. Immutable; a change creates a new record.
 *
 * @param theme {@code ui.theme}
 * @param window {@code window.*}: the main window's remembered placement
 * @param debugLogFile {@code log.debugFile}: write the log at DEBUG to a file from the start
 * @param options the Preferences dialog's settings
 * @param state remembered UI state
 * @param shortcuts {@code shortcut.*}: keys set in place of commands' defaults
 * @param appearance the theme file and the font family
 * @param tips {@code help.*}: Tip of the Day's startup choice and next tip
 */
public record Preferences(
    ThemeMode theme,
    WindowState window,
    boolean debugLogFile,
    Options options,
    UiState state,
    ShortcutOverrides shortcuts,
    Appearance appearance,
    TipSettings tips) {
  /** The settings when the file is missing or malformed. */
  public static final Preferences DEFAULTS =
      new Preferences(
          ThemeMode.LIGHT,
          WindowState.UNKNOWN,
          false,
          Options.DEFAULTS,
          UiState.DEFAULTS,
          ShortcutOverrides.NONE,
          Appearance.DEFAULTS,
          TipSettings.DEFAULTS);

  /** Checks the components. */
  public Preferences {
    if (theme == null
        || window == null
        || options == null
        || state == null
        || shortcuts == null
        || appearance == null
        || tips == null) {
      throw new IllegalArgumentException("every component is required");
    }
  }

  /**
   * Returns a copy with another theme.
   *
   * @param mode the theme
   * @return the copy
   */
  public Preferences withTheme(ThemeMode mode) {
    return new Preferences(mode, window, debugLogFile, options, state, shortcuts, appearance, tips);
  }

  /**
   * Returns a copy with another window placement.
   *
   * @param value the placement
   * @return the copy
   */
  public Preferences withWindow(WindowState value) {
    return new Preferences(theme, value, debugLogFile, options, state, shortcuts, appearance, tips);
  }

  /**
   * Returns a copy with the debug log file on or off.
   *
   * @param on true to write the file from the start
   * @return the copy
   */
  public Preferences withDebugLogFile(boolean on) {
    return new Preferences(theme, window, on, options, state, shortcuts, appearance, tips);
  }

  /**
   * Returns a copy with other dialog settings.
   *
   * @param value the settings
   * @return the copy
   */
  public Preferences withOptions(Options value) {
    return new Preferences(theme, window, debugLogFile, value, state, shortcuts, appearance, tips);
  }

  /**
   * Returns a copy with other remembered state.
   *
   * @param value the state
   * @return the copy
   */
  public Preferences withState(UiState value) {
    return new Preferences(
        theme, window, debugLogFile, options, value, shortcuts, appearance, tips);
  }

  /**
   * Returns a copy with other key overrides.
   *
   * @param value the overrides
   * @return the copy
   */
  public Preferences withShortcuts(ShortcutOverrides value) {
    return new Preferences(theme, window, debugLogFile, options, state, value, appearance, tips);
  }

  /**
   * Returns a copy with another theme file and font family.
   *
   * @param value the settings
   * @return the copy
   */
  public Preferences withAppearance(Appearance value) {
    return new Preferences(theme, window, debugLogFile, options, state, shortcuts, value, tips);
  }

  /**
   * Returns a copy with other Tip of the Day settings.
   *
   * @param value the settings
   * @return the copy
   */
  public Preferences withTips(TipSettings value) {
    return new Preferences(
        theme, window, debugLogFile, options, state, shortcuts, appearance, value);
  }
}
