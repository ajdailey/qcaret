package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.PathFormException;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's wording for a path {@code SafePaths} rejects by its text, shared by every notice that
 * names such a path: opening, Open Path at Caret, save, Save As, Save a Copy, Export as HTML, a
 * checksum list's save and entries, the HyperSearch folder, the workspace browser's path field, New
 * File, New Folder and Rename, and Open Settings Folder and Open Log Folder. The log keeps the
 * exception's message.
 */
final class PathFormText {
  private static final Logger LOG = LoggerFactory.getLogger(PathFormText.class);

  private PathFormText() {}

  /**
   * Checks a path the user gave by its text ({@link SafePaths#checkForm(String)}), before any file
   * system access, and logs a rejection at WARN with the exception's message.
   *
   * @param path the path text
   * @throws PathFormException when the text is rejected
   */
  @WorkerThread
  static void check(String path) throws PathFormException {
    try {
      SafePaths.checkForm(path);
    } catch (PathFormException e) {
      LOG.warn("Path {} refused: {}", LogText.oneLine(path), LogText.oneLine(e.getMessage()));
      throw e;
    }
  }

  /**
   * Checks a path's text as {@link #check} does.
   *
   * @param path the path text
   * @return the reason for the notice ({@link #reason}), or null when the text is accepted
   */
  @WorkerThread
  static String refusal(String path) {
    try {
      check(path);
      return null;
    } catch (PathFormException e) {
      return reason(e);
    }
  }

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
