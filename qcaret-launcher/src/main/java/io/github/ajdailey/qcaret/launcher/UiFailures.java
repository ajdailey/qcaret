package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.config.ErrorExitMarker;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Display's runtime-exception and error handlers. Each failure of a listener, {@code asyncExec}
 * or timer task on the UI thread is logged at ERROR with its stack and the event loop continues.
 * More than {@link #LIMIT} runtime exceptions within {@link #WINDOW_NANOS}, or any {@link Error},
 * writes the {@link ErrorExitMarker} on the UI thread, then runs the quit once and makes {@link
 * #failed} true. A failure after that is logged and starts no second quit. A quit that throws is
 * logged at ERROR and makes {@link #quitFailed} true, which ends the event loop: the windows that
 * quit hid are never disposed. Confined to the UI thread.
 */
final class UiFailures {
  /** Runtime exceptions tolerated within {@link #WINDOW_NANOS}. */
  static final int LIMIT = 10;

  /** One second. */
  static final long WINDOW_NANOS = 1_000_000_000L;

  private static final Logger LOG = LoggerFactory.getLogger(UiFailures.class);

  private final LongSupplier clock;
  private final Runnable quit;
  private final ErrorExitMarker marker;
  // The last LIMIT failure times, oldest at next once full.
  private final long[] times = new long[LIMIT];
  private int count;
  private int next;
  private boolean failed;
  private boolean quitFailed;

  /**
   * Creates the handlers.
   *
   * @param clock nanosecond time, as {@link System#nanoTime}
   * @param quit the quit that asks nothing; must return without waiting
   * @param marker written before the quit
   */
  UiFailures(LongSupplier clock, Runnable quit, ErrorExitMarker marker) {
    this.clock = clock;
    this.quit = quit;
    this.marker = marker;
  }

  /**
   * Handles an exception from a UI-thread task.
   *
   * @param failure the exception
   */
  void runtime(Exception failure) {
    LOG.error("UI task failed", failure);
    long now = clock.getAsLong();
    boolean over = count == LIMIT && now - times[next] <= WINDOW_NANOS;
    times[next] = now;
    next = (next + 1) % LIMIT;
    count = Math.min(count + 1, LIMIT);
    if (over && !failed) {
      LOG.error("More than {} UI task failures within 1 s; quitting", LIMIT);
      quit();
    }
  }

  /**
   * Handles an error from a UI-thread task.
   *
   * @param failure the error
   */
  void error(Error failure) {
    LOG.error(
        failed ? "UI task failed with an error" : "UI task failed with an error; quitting",
        failure);
    quit();
  }

  /**
   * Returns whether the failures ran the quit.
   *
   * @return true once the quit ran from here
   */
  boolean failed() {
    return failed;
  }

  /**
   * Returns whether the quit threw.
   *
   * @return true once the quit run from here threw
   */
  boolean quitFailed() {
    return quitFailed;
  }

  private void quit() {
    if (failed) {
      return;
    }
    failed = true;
    marker.write();
    try {
      quit.run();
    } catch (Exception | Error e) {
      quitFailed = true;
      LOG.error("Quit failed; ending the event loop", e);
    }
  }
}
