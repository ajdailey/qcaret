package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.io.PathFormException;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PathFormText} and the notices that use it, on any host, with the rejections {@link
 * SafePaths} raises for Windows.
 */
class PathFormTextTest {
  private static final OsTraits WINDOWS = OsTraits.of("Windows 11");

  @TempDir Path temp;

  /**
   * A device name names the device reason although the path also reads as missing on disk: in
   * QLogg's Windows gate {@code CON} and {@code logs\nul.txt} showed "the file does not exist".
   */
  @Test
  void aWindowsDeviceNameNamesTheDeviceReasonBeforeTheDiskReason() throws Exception {
    Files.createDirectory(temp.resolve("notes"));
    String device = UiText.OPEN_REASON_DEVICE.text();
    assertEquals(
        UiText.OPEN_FAILED.format("CON", device),
        OpenFailure.text(temp.resolve("CON"), refusal("CON")));
    assertEquals(
        UiText.OPEN_FAILED.format("nul.txt", device),
        OpenFailure.text(temp.resolve("notes").resolve("nul.txt"), refusal("notes\\nul.txt")));
    assertEquals(device, ChecksumRun.reason(refusal("COM1")));
    assertEquals(
        UiText.OPEN_FAILED.format("missing.txt", UiText.OPEN_REASON_MISSING.text()),
        OpenFailure.text(
            temp.resolve("missing.txt"),
            new NoSuchFileException(temp.resolve("missing.txt") + "")));
    assertEquals("access denied", ChecksumRun.reason(new AccessDeniedException("mock")));
  }

  @Test
  void eachRejectedFormHasItsOwnWording() {
    assertEquals(UiText.OPEN_REASON_DEVICE.text(), PathFormText.reason(refusal("CON")));
    assertEquals(UiText.OPEN_REASON_EMPTY.text(), PathFormText.reason(refusal("")));
    assertEquals(UiText.OPEN_REASON_NUL.text(), PathFormText.reason(refusal("a\0b")));
    assertEquals(
        UiText.OPEN_REASON_INVALID.text(),
        PathFormText.message(
            new PathFormException(PathFormException.Reason.INVALID, "Not a valid path", null)));
    IOException other = new IOException("mock\nfailure");
    assertNull(PathFormText.reason(other));
    assertEquals("mock_failure", PathFormText.message(other));
  }

  private static PathFormException refusal(final String path) {
    return assertThrows(
        PathFormException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            SafePaths.checkForm(path, WINDOWS);
          }
        },
        path);
  }
}
