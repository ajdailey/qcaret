package io.github.ajdailey.qcaret.app.log;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects UI-thread stalls. A {@code qcaret-watchdog-} thread posts a runnable through the {@link
 * UiDispatcher} every {@code intervalMillis}; when it has not run after {@code boundMillis}, the
 * watchdog takes the UI thread's stack and reports the stall, then reports the total delay once the
 * runnable runs. It never interrupts the UI thread. The thread ends at {@link #stop} or when the
 * workers close.
 */
public final class EventLoopWatchdog {
  /** Interval between probes. */
  public static final long INTERVAL_MILLIS = 250;

  /** Dispatch delay reported as a stall. */
  public static final long BOUND_MILLIS = 100;

  private static final Logger LOG = LoggerFactory.getLogger(EventLoopWatchdog.class);

  /** Receives stalls on the watchdog thread. */
  public interface StallListener {
    /**
     * A probe waited past the bound.
     *
     * @param millis the wait so far
     * @param uiStack the UI thread's stack when the bound passed
     */
    void stalled(long millis, StackTraceElement[] uiStack);

    /**
     * The stalled probe ran.
     *
     * @param millis its whole dispatch delay
     */
    void recovered(long millis);
  }

  /** Logs a stall at WARN with the UI thread's stack, and the recovery at INFO. */
  public static final StallListener LOGGING =
      new StallListener() {
        @Override
        public void stalled(long millis, StackTraceElement[] uiStack) {
          UiThreadStack stack = new UiThreadStack();
          stack.setStackTrace(uiStack);
          LOG.warn("UI thread stalled for {} ms", millis, stack);
        }

        @Override
        public void recovered(long millis) {
          LOG.info("UI thread responded after {} ms", millis);
        }
      };

  /**
   * Reports whether the watchdog's logger takes DEBUG, as {@code -d 2} and above set it.
   *
   * @return true when the watchdog should run
   */
  public static boolean enabled() {
    return LOG.isDebugEnabled();
  }

  /** Carries the UI thread's stack into the log; not thrown. */
  private static final class UiThreadStack extends Exception {
    private static final long serialVersionUID = 1L;

    UiThreadStack() {
      super("UI thread stack", null, false, true);
    }
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final Thread uiThread;
  private final long intervalMillis;
  private final long boundMillis;
  private final StallListener listener;
  private final CountDownLatch stopped = new CountDownLatch(1);

  /**
   * Creates the watchdog.
   *
   * @param workers runs the watchdog thread
   * @param ui posts the probes
   * @param uiThread the thread whose stack a stall reports
   * @param intervalMillis interval between probes
   * @param boundMillis dispatch delay reported as a stall
   * @param listener receives stalls
   */
  public EventLoopWatchdog(
      Workers workers,
      UiDispatcher ui,
      Thread uiThread,
      long intervalMillis,
      long boundMillis,
      StallListener listener) {
    this.workers = workers;
    this.ui = ui;
    this.uiThread = uiThread;
    this.intervalMillis = intervalMillis;
    this.boundMillis = boundMillis;
    this.listener = listener;
  }

  /**
   * Starts the watchdog thread.
   *
   * @return false when the workers are closed
   */
  public boolean start() {
    return workers.tryStart(
        Workers.Kind.WATCHDOG,
        new Runnable() {
          @Override
          public void run() {
            watch();
          }
        });
  }

  /** Ends the watchdog thread within one interval. */
  public void stop() {
    stopped.countDown();
  }

  private boolean running() {
    return stopped.getCount() > 0 && !workers.isClosed();
  }

  private void watch() {
    try {
      while (running()) {
        probe();
        if (stopped.await(intervalMillis, TimeUnit.MILLISECONDS)) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void probe() throws InterruptedException {
    final CountDownLatch ran = new CountDownLatch(1);
    long posted = System.nanoTime();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran.countDown();
          }
        });
    if (ran.await(boundMillis, TimeUnit.MILLISECONDS)) {
      return;
    }
    listener.stalled(elapsed(posted), uiThread.getStackTrace());
    while (running()) {
      if (ran.await(intervalMillis, TimeUnit.MILLISECONDS)) {
        listener.recovered(elapsed(posted));
        return;
      }
    }
  }

  private static long elapsed(long since) {
    return (System.nanoTime() - since) / 1_000_000L;
  }
}
