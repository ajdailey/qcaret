package io.github.ajdailey.qcaret.core.testkit;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Bounded polling for state another thread produces. */
public final class Poll {
  private static final long INTERVAL_MILLIS = 10;

  private Poll() {}

  /**
   * Checks a condition until it holds or the bound passes, sleeping briefly between checks.
   *
   * @param condition the state awaited
   * @param bound the longest wait
   * @return true when the condition held within the bound
   * @throws InterruptedException when interrupted while waiting
   */
  public static boolean until(BooleanSupplier condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline >= 0) {
        return false;
      }
      Thread.sleep(INTERVAL_MILLIS);
    }
    return true;
  }
}
