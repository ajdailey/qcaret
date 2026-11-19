package io.github.ajdailey.qcaret.core.format;

/** A range that did not format, at an offset in the range. */
final class FormatException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int at;

  /**
   * Creates the failure.
   *
   * @param at the offset in the range
   * @param reason the message shown
   */
  FormatException(int at, String reason) {
    super(reason, null, false, false);
    this.at = at;
  }

  /** Returns the offset in the range. */
  int at() {
    return at;
  }
}
