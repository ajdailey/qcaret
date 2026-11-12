package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The event loop's failure bound, on given times. */
class LoopFailuresTest {
  @Test
  void theLimitWithinTheWindowEndsTheLoop() {
    LoopFailures failures = new LoopFailures();
    for (int i = 1; i < LoopFailures.LIMIT; i++) {
      assertTrue(failures.tolerate(i), "failure " + i);
    }
    assertFalse(failures.tolerate(LoopFailures.WINDOW_NANOS));
  }

  @Test
  void failuresSpreadPastTheWindowContinue() {
    LoopFailures failures = new LoopFailures();
    long step = LoopFailures.WINDOW_NANOS / (LoopFailures.LIMIT - 1) + 1;
    for (int i = 0; i < 5 * LoopFailures.LIMIT; i++) {
      assertTrue(failures.tolerate(i * step), "failure " + i);
    }
  }
}
