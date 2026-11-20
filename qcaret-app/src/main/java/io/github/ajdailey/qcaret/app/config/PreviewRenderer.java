package io.github.ajdailey.qcaret.app.config;

import java.util.Locale;

/** {@code markdown.previewRenderer}: what draws the Markdown preview of every window. */
public enum PreviewRenderer {
  /** {@code basic}: styled text built in QCaret. */
  BASIC,
  /** {@code browser}: HTML in the platform's browser control. */
  BROWSER;

  /**
   * Returns the value as {@code qcaret.conf} holds it.
   *
   * @return {@code basic} or {@code browser}
   */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a stored value.
   *
   * @param value the value, or null
   * @return the choice; null for any other value
   */
  public static PreviewRenderer parse(String value) {
    if (value == null) {
      return null;
    }
    for (PreviewRenderer choice : values()) {
      if (choice.key().equals(value.strip())) {
        return choice;
      }
    }
    return null;
  }
}
