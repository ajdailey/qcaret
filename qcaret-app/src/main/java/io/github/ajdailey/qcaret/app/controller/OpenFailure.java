package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.DevicePathException;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * The error text shown when a file does not open. {@code AccessDeniedException} and {@code
 * NoSuchFileException} carry only the path as their message, so they map to a fixed reason, as does
 * {@link DevicePathException}, whose message is the log's wording; another {@code
 * FileSystemException} shows its reason without the path, and any other failure its message. Reads
 * nothing from disk, so it runs on the UI thread.
 */
final class OpenFailure {
  private OpenFailure() {}

  /**
   * Returns {@code Cannot open <name>: <reason>}.
   *
   * @param path the path that did not open
   * @param error the failure
   * @return the one-line text
   */
  static String text(Path path, IOException error) {
    Path name = path.getFileName();
    return UiText.OPEN_FAILED.format(
        LogText.oneLine(String.valueOf(name == null ? path : name)), reason(error));
  }

  private static String reason(IOException error) {
    if (error instanceof AccessDeniedException) {
      return UiText.OPEN_REASON_DENIED.text();
    }
    if (error instanceof NoSuchFileException) {
      return UiText.OPEN_REASON_MISSING.text();
    }
    if (error instanceof DevicePathException) {
      return UiText.OPEN_REASON_DEVICE.text();
    }
    String reason =
        error instanceof FileSystemException system && system.getReason() != null
            ? system.getReason()
            : error.getMessage();
    return reason == null ? error.getClass().getSimpleName() : LogText.oneLine(reason);
  }
}
