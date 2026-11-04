package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
