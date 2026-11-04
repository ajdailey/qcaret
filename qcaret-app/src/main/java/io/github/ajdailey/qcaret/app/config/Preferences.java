package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;

/**
 * The settings in {@code qcaret.conf}. Immutable; a change creates a new record.
 *
 * @param theme {@code ui.theme}
 * @param window {@code window.*}: the main window's remembered placement
 * @param debugLogFile {@code log.debugFile}: write the log at DEBUG to a file from the start
 */
public record Preferences(ThemeMode theme, WindowState window, boolean debugLogFile) {
  /** The settings when the file is missing or malformed. */
  public static final Preferences DEFAULTS =
      new Preferences(ThemeMode.LIGHT, WindowState.UNKNOWN, false);

  /** Checks the components. */
  public Preferences {
    if (theme == null || window == null) {
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
    return new Preferences(mode, window, debugLogFile);
  }

  /**
   * Returns a copy with another window placement.
   *
   * @param value the placement
   * @return the copy
   */
  public Preferences withWindow(WindowState value) {
    return new Preferences(theme, value, debugLogFile);
  }

  /**
   * Returns a copy with the debug log file on or off.
   *
   * @param on true to write the file from the start
   * @return the copy
   */
  public Preferences withDebugLogFile(boolean on) {
    return new Preferences(theme, window, on);
  }
}
