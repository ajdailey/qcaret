package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.core.text.LogText;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JVM shutdown hook's task, for an exit that skips the ordered quit (SIGTERM, logoff, a parent
 * ending the process). Unless the quit has finished, it asks for the quit, which runs on the UI
 * thread and its lifecycle worker (cancel, save the settings and the session, close the documents
 * and the workers), and waits up to its bound. Past the bound it logs the threads still running and
 * returns, and the JVM halts. Either way it then runs {@code release} (removing the handoff
 * endpoint). It touches no widget: the request goes through the UI dispatcher, which drops it once
 * the Display is disposed. A second run, or a run after the quit finished, only releases.
 */
final class ShutdownHook implements Runnable {
  /** Longest wait for the quit. */
  static final Duration BOUND = Duration.ofSeconds(3);

  private static final Logger LOG = LoggerFactory.getLogger(ShutdownHook.class);

  private final Runnable requestQuit;
  private final CountDownLatch finished;
  private final Runnable release;
  private final Duration bound;
  private final Supplier<String> survivors;

  /**
   * Creates the task.
   *
   * @param requestQuit posts the ordered quit to the UI thread; returns at once
   * @param finished counted down when the quit has saved and closed the workers
   * @param release removes the handoff endpoint; idempotent
   * @param bound the longest wait
   * @param survivors names the threads still running
   */
  ShutdownHook(
      Runnable requestQuit,
      CountDownLatch finished,
      Runnable release,
      Duration bound,
      Supplier<String> survivors) {
    this.requestQuit = requestQuit;
    this.finished = finished;
    this.release = release;
    this.bound = bound;
    this.survivors = survivors;
  }

  @Override
  public void run() {
    try {
      if (finished.getCount() > 0) {
        LOG.info("Exit requested by the system; quitting");
        requestQuit.run();
        boolean done;
        try {
          done = finished.await(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          done = false;
        }
        if (!done) {
          LOG.warn(
              "Quit did not finish within {}; forced exit with threads running: {}",
              bound,
              LogText.oneLine(survivors.get()));
        }
      }
    } finally {
      release.run();
    }
  }
}
