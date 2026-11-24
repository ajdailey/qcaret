package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.PathFormException;
import io.github.ajdailey.qcaret.core.text.LogText;

/**
 * The user's wording for a path {@code SafePaths} rejects by its text, shared by every notice that
 * names such a path: opening, Open Path at Caret, the workspace browser's New File, New Folder and
 * Rename, a checksum list's entries, and Open Settings Folder and Open Log Folder. The log keeps
 * the exception's message.
 */
final class PathFormText {
  private PathFormText() {}

  /**
   * Returns the reason for a rejected path's text.
   *
   * @param error the failure
   * @return the reason, or null when the failure is not a {@link PathFormException}
   */
  static String reason(Throwable error) {
    if (!(error instanceof PathFormException form)) {
      return null;
    }
    return switch (form.reason()) {
      case EMPTY -> UiText.OPEN_REASON_EMPTY.text();
      case NUL -> UiText.OPEN_REASON_NUL.text();
      case DEVICE -> UiText.OPEN_REASON_DEVICE.text();
      case INVALID -> UiText.OPEN_REASON_INVALID.text();
    };
  }

  /**
   * Returns {@link #reason}, or the failure's message on one line when it is not a rejected path.
   *
   * @param error the failure
   * @return the text
   */
  static String message(Throwable error) {
    String reason = reason(error);
    return reason != null ? reason : LogText.oneLine(error.getMessage());
  }
}
