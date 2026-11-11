package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** The notice for lines a line command left unchanged so that a CR and an LF stay apart. */
final class UnchangedLines {
  private UnchangedLines() {}

  /**
   * Returns the notice.
   *
   * @param lines the lines left unchanged, at least 1
   * @return the notice text
   */
  static String notice(int lines) {
    return UiText.count(lines, UiText.LINES_UNCHANGED_ONE, UiText.LINES_UNCHANGED);
  }
}
