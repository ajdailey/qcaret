package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;

/**
 * The settings in {@code qcaret.conf}. Immutable; a change creates a new record.
 *
 * @param theme {@code ui.theme}
 * @param window {@code window.*}: the main window's remembered placement
 * @param debugLogFile {@code log.debugFile}: write the log at DEBUG to a file from the start
 * @param completionChars {@code editor.completionChars}: word chars typed before the completion
 *     popup opens on its own, 0 for never
 */
public record Preferences(
    ThemeMode theme, WindowState window, boolean debugLogFile, int completionChars) {
  /** Largest {@code completionChars}. */
  public static final int MAX_COMPLETION_CHARS = 20;

  /** The settings when the file is missing or malformed. */
  public static final Preferences DEFAULTS =
      new Preferences(ThemeMode.LIGHT, WindowState.UNKNOWN, false, 0);

  /** Checks the components. */
  public Preferences {
    if (theme == null || window == null) {
      throw new IllegalArgumentException("every component is required");
    }
    if (completionChars < 0 || completionChars > MAX_COMPLETION_CHARS) {
      throw new IllegalArgumentException("completionChars " + completionChars);
    }
  }

  /**
   * Returns a copy with another theme.
   *
   * @param mode the theme
   * @return the copy
   */
  public Preferences withTheme(ThemeMode mode) {
    return new Preferences(mode, window, debugLogFile, completionChars);
  }

  /**
   * Returns a copy with another window placement.
   *
   * @param value the placement
   * @return the copy
   */
  public Preferences withWindow(WindowState value) {
    return new Preferences(theme, value, debugLogFile, completionChars);
  }

  /**
   * Returns a copy with the debug log file on or off.
   *
   * @param on true to write the file from the start
   * @return the copy
   */
  public Preferences withDebugLogFile(boolean on) {
    return new Preferences(theme, window, on, completionChars);
  }

  /**
   * Returns a copy with another completion trigger.
   *
   * @param chars word chars typed before the popup opens, 0 for never
   * @return the copy
   */
  public Preferences withCompletionChars(int chars) {
    return new Preferences(theme, window, debugLogFile, chars);
  }
}
