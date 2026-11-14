package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.util.Locale;

/** {@code editor.newLineEnding}: the line ending of a document with no line break to detect. */
public enum NewLineEnding {
  /** {@code lf}. */
  LF,
  /** {@code crlf}. */
  CRLF,
  /** {@code platform}: CRLF on Windows, else LF. */
  PLATFORM;

  /**
   * Returns the value as {@code qcaret.conf} holds it.
   *
   * @return {@code lf}, {@code crlf} or {@code platform}
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
  public static NewLineEnding parse(String value) {
    if (value == null) {
      return null;
    }
    for (NewLineEnding choice : values()) {
      if (choice.key().equals(value.strip())) {
        return choice;
      }
    }
    return null;
  }

  /**
   * Returns the line ending this choice stands for here.
   *
   * @param separator the platform's line separator, {@link System#lineSeparator()}
   * @return the ending
   */
  public LineEnding ending(String separator) {
    return switch (this) {
      case LF -> LineEnding.LF;
      case CRLF -> LineEnding.CRLF;
      case PLATFORM -> "\r\n".equals(separator) ? LineEnding.CRLF : LineEnding.LF;
    };
  }
}
