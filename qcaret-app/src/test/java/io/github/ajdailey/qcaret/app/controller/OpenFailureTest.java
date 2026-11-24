package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.PathFormException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The open failure text: a fixed reason for a denied or missing file and a Windows device path,
 * never the path alone.
 */
class OpenFailureTest {
  @TempDir Path temp;

  @Test
  void eachFailureNamesTheFileAndAReason() {
    Path file = temp.resolve("x.log");
    String path = file.toString();
    assertEquals(
        UiText.OPEN_FAILED.format("x.log", UiText.OPEN_REASON_DENIED.text()),
        OpenFailure.text(file, new AccessDeniedException(path)));
    assertEquals(
        UiText.OPEN_FAILED.format("x.log", UiText.OPEN_REASON_MISSING.text()),
        OpenFailure.text(file, new NoSuchFileException(path)));
    assertEquals(
        "Cannot open CON: the path names a Windows device",
        OpenFailure.text(
            Path.of("CON"),
            new PathFormException(
                PathFormException.Reason.DEVICE, "Path names a Windows device", null)));
    assertEquals(
        UiText.OPEN_FAILED.format("x.log", "Device busy"),
        OpenFailure.text(file, new FileSystemException(path, null, "Device busy")));
    assertEquals(
        UiText.OPEN_FAILED.format("x.log", "Is a directory"),
        OpenFailure.text(file, new IOException("Is a directory")));
    assertEquals(
        UiText.OPEN_FAILED.format("x.log", "IOException"),
        OpenFailure.text(file, new IOException()));
  }
}
