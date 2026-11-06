package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.watch.FileCheck;
import io.github.ajdailey.qcaret.core.watch.FileMonitor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the files of one window's open documents for changes by other programs. A {@link
 * FileMonitor} names the files due; its {@code qcaret-watch-} thread runs {@link FileCheck} for
 * each {@link Watch} on the path the user opened and posts what differs from the watch's baseline
 * to the {@link Listener} on the UI thread.
 *
 * <p>Each watch has a generation. {@link #pause}, {@link #resume} and {@link #accept} raise it on
 * the UI thread, and a result checked against an older generation is dropped, so a check that
 * overlaps a save or a reload never reports the document's own write. A paused watch is not
 * checked. A file that cannot be read is logged at DEBUG and checked again at the next poll.
 *
 * <p>A directory event wakes a check while another program is still writing, and a truncate and a
 * write are separate events, so a changed file is reported once its stamp holds for {@value
 * #QUIET_MILLIS} ms. One write then asks or reloads once. A file still changing after {@value
 * #QUIET_ATTEMPTS} waits is reported as it stands, so a log that never stops growing still shows.
 */
final class ExternalChanges {
  private static final Logger LOG = LoggerFactory.getLogger(ExternalChanges.class);

  /** Milliseconds a changed file's stamp must hold before the change is reported. */
  static final long QUIET_MILLIS = 100;

  /** Waits for a changed file to go quiet before it is reported anyway. */
  static final int QUIET_ATTEMPTS = 5;

  /** Receives the results of the current generation on the UI thread. */
  interface Listener {
    /**
     * A check found other bytes, no file, or the same bytes under a newer stamp.
     *
     * @param watch the watch
     * @param result what the check found
     */
    void found(Watch watch, FileCheck.Result result);
  }

  /** One document's file as the checks see it. */
  static final class Watch {
    private volatile Path path;
    private volatile FileBaseline baseline;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean paused;
    private volatile boolean missing;

    private Watch(Path path, FileBaseline baseline) {
      this.path = path;
      this.baseline = baseline;
    }

    /** Returns the path the user opened. */
    Path path() {
      return path;
    }

    /** Returns what the document last matched, or null when no file existed. */
    FileBaseline baseline() {
      return baseline;
    }

    /** Marks the file missing; a missing file is not reported missing again. */
    void setMissing(boolean value) {
      missing = value;
    }
  }

  private final UiDispatcher ui;
  private final long quietMillis;
  private final Listener listener;
  private final FileMonitor monitor;
  private final List<Watch> watches = new CopyOnWriteArrayList<Watch>();
  private final CancellationToken token = new CancellationToken();
  private final AtomicLong passes = new AtomicLong();

  ExternalChanges(Workers workers, UiDispatcher ui, long pollMillis, Listener listener) {
    this(workers, ui, pollMillis, QUIET_MILLIS, listener);
  }

  /**
   * Creates the checks.
   *
   * @param workers starts the monitor's thread
   * @param ui receives the results
   * @param pollMillis interval between checks of every open file
   * @param quietMillis how long a changed file's stamp must hold
   * @param listener receives the results
   */
  ExternalChanges(
      Workers workers, UiDispatcher ui, long pollMillis, long quietMillis, Listener listener) {
    this.ui = ui;
    this.quietMillis = quietMillis;
    this.listener = listener;
    this.monitor =
        new FileMonitor(
            workers,
            pollMillis,
            new FileMonitor.Listener() {
              @Override
              @WorkerThread
              public void check(List<Path> files) {
                checkFiles(files);
              }
            });
  }

  /** Sets the interval between checks of every open file ({@code file.pollSeconds}). */
  void setPollMillis(long pollMillis) {
    monitor.setPollMillis(pollMillis);
  }

  /** Starts checking a file against a baseline, null when no file exists yet. */
  Watch watch(Path path, FileBaseline baseline) {
    Watch watch = new Watch(path, baseline);
    watches.add(watch);
    monitor.watch(path);
    return watch;
  }

  /** Stops checking a watch's file. */
  void unwatch(Watch watch) {
    if (watches.remove(watch)) {
      watch.generation.incrementAndGet();
      monitor.unwatch(watch.path);
    }
  }

  /** Stops checking while the document writes or reads its file; results in flight are dropped. */
  void pause(Watch watch) {
    watch.paused = true;
    watch.generation.incrementAndGet();
  }

  /** Checks again against a new path and baseline, after a save or a reload. */
  void resume(Watch watch, Path path, FileBaseline baseline) {
    if (!path.equals(watch.path) && watches.contains(watch)) {
      monitor.watch(path);
      monitor.unwatch(watch.path);
    }
    watch.path = path;
    watch.baseline = baseline;
    watch.missing = false;
    watch.paused = false;
    watch.generation.incrementAndGet();
  }

  /** Takes a newer baseline the document has not loaded: same bytes, or a change the user kept. */
  void accept(Watch watch, FileBaseline baseline) {
    watch.baseline = baseline;
    watch.generation.incrementAndGet();
  }

  /** Checks every file now, as on window activation. */
  void checkAll() {
    monitor.checkAll();
  }

  /** Checks one watch's file now. */
  void check(Watch watch) {
    monitor.check(watch.path);
  }

  /** Stops every check. */
  void close() {
    token.cancel();
    monitor.close();
    watches.clear();
  }

  /** Reports whether the monitor's thread runs; for tests. */
  boolean running() {
    return monitor.running();
  }

  /** Counts the finished calls of the monitor's listener; for tests. */
  long passes() {
    return passes.get();
  }

  @WorkerThread
  private void checkFiles(List<Path> files) {
    try {
      checkEach(files);
    } finally {
      passes.incrementAndGet();
    }
  }

  @WorkerThread
  private void checkEach(List<Path> files) {
    List<Found> changed = new ArrayList<Found>();
    for (Path file : files) {
      for (Watch watch : watches) {
        if (watch.paused || !file.equals(watch.path)) {
          continue;
        }
        long generation = watch.generation.get();
        Found found = check(new Found(watch, generation, watch.baseline, null));
        if (found == null) {
          return;
        }
        if (found.result() == null) {
          continue;
        }
        if (found.result().state() == FileCheck.State.CHANGED) {
          changed.add(found);
        } else {
          post(found);
        }
      }
    }
    settle(changed);
  }

  /** Waits for changed files to go quiet, then reports each one. */
  @WorkerThread
  private void settle(List<Found> changed) {
    List<Found> waiting = changed;
    for (int attempt = 0; attempt < QUIET_ATTEMPTS && !waiting.isEmpty(); attempt++) {
      if (!monitor.pause(quietMillis) || token.isCanceled()) {
        return;
      }
      List<Found> moving = new ArrayList<Found>();
      for (Found found : waiting) {
        Found again = quiet(found) ? found : check(found);
        if (again == null) {
          return;
        }
        if (again == found) {
          post(found);
        } else if (again.result() != null) {
          if (again.result().state() == FileCheck.State.CHANGED) {
            moving.add(again);
          } else {
            post(again);
          }
        }
      }
      waiting = moving;
    }
    for (Found found : waiting) {
      post(found);
    }
  }

  /** Reports whether a changed file still has the stamp the check read; unreadable means no. */
  @WorkerThread
  private static boolean quiet(Found found) {
    try {
      FileStamp now = FileStamp.read(found.watch().path);
      return found.result().current().stamp().equals(now);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Checks a watch's file.
   *
   * @return the check's outcome, with a null result when there is nothing to report; null when
   *     canceled
   */
  @WorkerThread
  private Found check(Found found) {
    Watch watch = found.watch();
    FileBaseline baseline = found.baseline();
    FileCheck.Result result;
    try {
      result = FileCheck.check(watch.path, baseline, token);
    } catch (IOException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Cannot check {}: {}",
            LogText.oneLine(String.valueOf(watch.path)),
            LogText.oneLine(e.getMessage()));
      }
      return new Found(watch, found.generation(), baseline, null);
    } catch (CancellationException e) {
      return null;
    }
    boolean nothing = result.state() == FileCheck.State.UNCHANGED && result.current() == baseline;
    boolean stillMissing = result.state() == FileCheck.State.MISSING && watch.missing;
    return new Found(watch, found.generation(), baseline, nothing || stillMissing ? null : result);
  }

  /** Posts a result to the listener, which sees it only if its generation is still current. */
  private void post(final Found found) {
    final Watch watch = found.watch();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (watch.generation.get() == found.generation()
                && !watch.paused
                && watches.contains(watch)) {
              listener.found(watch, found.result());
            }
          }
        });
  }

  /** One check of a watch's file, against the generation and baseline it read. */
  private record Found(
      Watch watch, long generation, FileBaseline baseline, FileCheck.Result result) {}
}
