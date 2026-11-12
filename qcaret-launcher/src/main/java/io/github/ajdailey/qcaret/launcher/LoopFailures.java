package io.github.ajdailey.qcaret.launcher;

/**
 * The event loop's bound on failed events. The loop logs a {@link RuntimeException} from one event
 * and continues; the {@value #LIMIT}th failure within {@value #WINDOW_NANOS} ns ends the loop, so a
 * defect that fails every event does not spin. Confined to the UI thread.
 */
final class LoopFailures {
  /** Failures within the window that end the loop. */
  static final int LIMIT = 10;

  /** The window, in nanoseconds. */
  static final long WINDOW_NANOS = 1_000_000_000L;

  private final long[] times = new long[LIMIT];
  private int next;
  private long total;

  /**
   * Records a failure.
   *
   * @param now the failure's {@link System#nanoTime}
   * @return true when the loop continues, false when this is the {@value #LIMIT}th failure within
   *     the window
   */
  boolean tolerate(long now) {
    times[next] = now;
    next = (next + 1) % LIMIT;
    total++;
    return total < LIMIT || now - times[next] >= WINDOW_NANOS;
  }
}
