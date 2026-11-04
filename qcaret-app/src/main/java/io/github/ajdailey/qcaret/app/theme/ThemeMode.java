package io.github.ajdailey.qcaret.app.theme;

import java.util.Locale;

/** The {@code ui.theme} setting. */
public enum ThemeMode {
  /** The light palette; the default. */
  LIGHT,
  /** The dark palette. */
  DARK,
  /** The palette that matches the system setting. */
  SYSTEM;

  /**
   * Returns the value written to the configuration.
   *
   * @return {@code light}, {@code dark} or {@code system}
   */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a configuration value.
   *
   * @param value the value; surrounding blanks are ignored
   * @return the mode, or null when the value names none
   */
  public static ThemeMode parse(String value) {
    for (ThemeMode mode : values()) {
      if (mode.key().equals(value.strip())) {
        return mode;
      }
    }
    return null;
  }
}
