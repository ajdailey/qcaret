package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.util.LimitExceededException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import org.junit.jupiter.api.Test;

class LogTextTest {
  @Test
  void replacesEveryLineBreakAndControl() {
    assertEquals("a_b_c_d_e_f_g_h", LogText.oneLine("a\rb\nc\u0085d\u2028e\u2029f\tg\u001bh"));
  }

  @Test
  void forgedEntryStaysOnOneLine() {
    assertEquals(
        "x.log_2026-01-01 ERROR forged", LogText.oneLine("x.log\n2026-01-01 ERROR forged"));
  }

  @Test
  void capsLength() {
    assertEquals(LogText.MAX_CHARS, LogText.oneLine("x".repeat(5000)).length());
  }

  @Test
  void nullIsText() {
    assertEquals("null", LogText.oneLine(null));
  }

  @Test
  void reasonLeavesOutThePaths() {
    assertEquals(
        "FileSystemException: Is a directory",
        LogText.reason(
            new FileSystemException("/mock/dir/a.tmp", "/mock/dir/a.conf", "Is a directory")));
    assertEquals(
        "AccessDeniedException",
        LogText.reason(new AccessDeniedException("/mock/dir/a.tmp", "/mock/dir/a.conf", null)));
    assertEquals("IOException", LogText.reason(new IOException("/mock/dir is not a directory")));
    assertEquals(
        "LimitExceededException: More than 4096 bytes",
        LogText.reason(new LimitExceededException(4096)));
    assertEquals(
        "FileSystemException: in_use",
        LogText.reason(new FileSystemException("/mock/a.conf", null, "in\nuse")));
  }
}
