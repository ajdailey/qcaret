package io.github.ajdailey.qcaret.core.text;

/** A line break kind. */
public enum LineEnding {
  /** Line feed, Unix and macOS. */
  LF("\n"),
  /** Carriage return plus line feed, Windows. */
  CRLF("\r\n"),
  /** Carriage return alone, classic Mac OS. */
  CR("\r");

  private final String text;

  LineEnding(String text) {
    this.text = text;
  }

  /**
   * Returns the break's chars.
   *
   * @return {@code "\n"}, {@code "\r\n"} or {@code "\r"}
   */
  public String text() {
    return text;
  }
}
