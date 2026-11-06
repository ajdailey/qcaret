package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.LoadTooLargeException;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * Opens files for one window. A {@code qcaret-io-} worker resolves the path to its real path, the
 * UI thread offers it to {@link Listener#resolved} (an open document ends the open there), and a
 * second worker reads the file; every result reaches the {@link Listener} on the UI thread. Before
 * reading, the worker estimates the load's heap cost ({@link DocumentFiles#estimate}) and reserves
 * it from the {@link MemoryBudget}; a load the budget cannot hold is refused unread, and a load
 * whose text outgrows the estimate past the budget is refused when it gets there. A loaded document
 * arrives with its rows ({@link DisplayProjection#layout}) and its reservation lowered to the
 * retained cost, which the listener owns from then on. {@link #close} cancels the running loads; a
 * load that finishes after it, on the worker or in a task already posted, drops its document and
 * releases its reservation, so no document reaches a closed window. Each open counts as one {@link
 * ActivityIndicator} operation until it ends: unread after {@link Listener#resolved}, refused,
 * failed, loaded, canceled or closed.
 *
 * <p>A load still reading {@value #PROGRESS_DELAY_MILLIS} ms after it started shows through {@link
 * Listener#progress}, with every other running load, until the last ends or {@link #cancelLoads}
 * stops them. A canceled load drops whatever it read and releases its reservation; {@link
 * Listener#canceled} replaces its result, also when the worker had already finished.
 */
public final class DocumentOpener {
  /** Receives load results on the UI thread. */
  public interface Listener {
    /**
     * A path resolved, before the file is read.
     *
     * @param requested the path as requested
     * @param path the validated path
     * @return true to end the open unread, false to load the file
     */
    boolean resolved(Path requested, Path path);

    /**
     * Chooses how a resolved file loads.
     *
     * @param path the validated path
     * @param requested the options the open asked for
     * @return the options to load with
     */
    LoadOptions options(Path path, LoadOptions requested);

    /**
     * A file loaded.
     *
     * @param path the validated path
     * @param file the document and what the load found
     * @param layout the document's rows without wrap
     * @param reserved bytes reserved for the document, released by the listener when it closes
     */
    void opened(Path path, LoadedFile file, DisplayProjection.Layout layout, long reserved);

    /**
     * A load was refused, before or during reading: the budget cannot hold it.
     *
     * @param path the validated path
     * @param cost the estimate, or the projection of a load refused while reading
     * @param available bytes the budget could still reserve
     * @param maxHeap the maximum heap size
     */
    void tooLarge(Path path, LoadCost cost, long available, long maxHeap);

    /**
     * A load failed; not called for a canceled load.
     *
     * @param path the validated path, or the path as requested when it failed validation
     * @param error the failure
     */
    void failed(Path path, IOException error);

    /**
     * A load was canceled by {@link #cancelLoads}; nothing else arrives for it.
     *
     * @param path the validated path
     */
    void canceled(Path path);

    /**
     * The running loads' progress changed, or they ran past the delay and show from now on.
     *
     * @param path the file when one load runs, else null
     * @param files the running loads
     * @param percent how much of their files was read, from 0 to 100
     */
    void progress(Path path, int files, int percent);

    /** No load shows any more. */
    void progressEnded();
  }

  /** How long a load reads before it shows. */
  static final int PROGRESS_DELAY_MILLIS = 300;

  /** A load past {@link Listener#resolved}; the list of them is confined to the UI thread. */
  private static final class Load {
    final Path path;
    final CancellationToken token;
    volatile long total;
    volatile long read;

    Load(Path path, CancellationToken token) {
      this.path = path;
      this.token = token;
    }
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final ActivityIndicator activity;
  private final Listener listener;
  private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
  private final Set<CancellationToken> waiting = ConcurrentHashMap.newKeySet();
  private final List<Load> loads = new ArrayList<Load>();
  private final AtomicBoolean reportPending = new AtomicBoolean();
  private final Runnable showLoads =
      new Runnable() {
        @Override
        public void run() {
          if (!closed && !loads.isEmpty()) {
            shown = true;
            shownPercent = -1;
            report();
          }
        }
      };
  private final Runnable reportLoads =
      new Runnable() {
        @Override
        public void run() {
          reportPending.set(false);
          report();
        }
      };
  private volatile boolean shown;
  private int shownPercent;
  private int shownFiles;
  private volatile boolean closed;

  /**
   * Creates the opener.
   *
   * @param workers starts the loads
   * @param ui runs the results on the UI thread
   * @param budget the documents' heap share
   * @param activity counts the running loads
   * @param listener receives the results
   */
  public DocumentOpener(
      Workers workers,
      UiDispatcher ui,
      MemoryBudget budget,
      ActivityIndicator activity,
      Listener listener) {
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
    this.activity = activity;
    this.listener = listener;
  }

  /**
   * Starts a load; called on the UI thread. A worker resolves the path ({@link SafePaths#userFile})
   * and the UI thread offers it to {@link Listener#resolved}; the file is read only when that
   * returns false. Does nothing once closed or once the workers are closed.
   *
   * @param path the file as the user named it
   * @param options the forced charset, fallback and undo bounds
   */
  public void open(final Path path, final LoadOptions options) {
    if (closed) {
      return;
    }
    final CancellationToken token = new CancellationToken();
    running.add(token);
    activity.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                boolean handedOn = false;
                try {
                  if (closed) {
                    return;
                  }
                  Path file = SafePaths.userFile(path);
                  handedOn = postResolved(path, file, options, token);
                } catch (IOException e) {
                  postFailed(path, e);
                } finally {
                  if (!handedOn) {
                    end(token);
                  }
                }
              }
            });
    if (!started) {
      end(token);
    }
  }

  /**
   * Posts the resolved path to the UI thread, which loads it or ends the open; false when closed.
   * An open waiting for the UI thread is in {@link #waiting}, and whichever of the posted task and
   * {@link #close} removes it ends it, so a task the dispatcher drops still ends.
   */
  private boolean postResolved(
      final Path requested,
      final Path file,
      final LoadOptions options,
      final CancellationToken token) {
    waiting.add(token);
    if (closed) {
      return !waiting.remove(token);
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!waiting.remove(token)) {
              return;
            }
            if (closed
                || listener.resolved(requested, file)
                || !load(file, listener.options(file, options), token)) {
              end(token);
            }
          }
        });
    return true;
  }

  /** Starts reading a resolved file on a worker, which ends the open; false when not started. */
  private boolean load(Path file, LoadOptions options, CancellationToken token) {
    Load load = new Load(file, token);
    track(load);
    boolean started = read(load, options);
    if (!started) {
      finished(load);
    }
    return started;
  }

  private boolean read(final Load load, final LoadOptions options) {
    final Path file = load.path;
    final CancellationToken token = load.token;
    return workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            try {
              if (closed) {
                return;
              }
              token.check();
              LoadCost cost = DocumentFiles.estimate(file, options.limits());
              load.total = cost.fileBytes();
              try (LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
                if (reservation == null) {
                  postTooLarge(load, cost, budget.available());
                  return;
                }
                reservation.setProgress(
                    new LongConsumer() {
                      @Override
                      public void accept(long bytes) {
                        load.read = bytes;
                        if (shown && reportPending.compareAndSet(false, true)) {
                          ui.asyncExec(reportLoads);
                        }
                      }
                    });
                LoadedFile loaded = DocumentFiles.load(file, options, reservation, token);
                DisplayProjection.Layout layout =
                    DisplayProjection.layout(loaded.document().text(), 0, token);
                long reserved = reservation.keep(loaded.retainedBytes());
                post(load, new Loaded(loaded, layout, reserved), null);
              }
            } catch (LoadTooLargeException e) {
              postTooLarge(load, e.cost(), budget.available());
            } catch (IOException e) {
              post(load, null, e);
            } catch (CancellationException e) {
              post(load, null, null);
            } finally {
              end(token);
            }
          }
        });
  }

  private void end(CancellationToken token) {
    running.remove(token);
    activity.setIdle();
  }

  /** Lists a load; the first of a run of loads starts the delay. */
  private void track(Load load) {
    loads.add(load);
    if (loads.size() == 1 && !shown) {
      ui.timerExec(PROGRESS_DELAY_MILLIS, showLoads);
    }
  }

  /** Takes a load off the list; the last one ends the delay and what shows. */
  private void finished(Load load) {
    if (!loads.remove(load)) {
      return;
    }
    if (loads.isEmpty()) {
      stopShowing();
    } else {
      report();
    }
  }

  private void stopShowing() {
    ui.cancelTimer(showLoads);
    if (shown) {
      shown = false;
      listener.progressEnded();
    }
  }

  /** Passes the loads' progress to the listener when it changed. */
  private void report() {
    if (!shown || closed || loads.isEmpty()) {
      return;
    }
    long read = 0;
    long total = 0;
    for (Load load : loads) {
      read += load.read;
      total += load.total;
    }
    int percent = total == 0 ? 0 : (int) Math.min(100, read * 100 / total);
    if (percent != shownPercent || loads.size() != shownFiles) {
      shownPercent = percent;
      shownFiles = loads.size();
      listener.progress(shownFiles == 1 ? loads.get(0).path : null, shownFiles, percent);
    }
  }

  /**
   * Cancels every load past {@link Listener#resolved} and stops showing them; each ends with {@link
   * Listener#canceled}, on a later UI turn. Called on the UI thread.
   */
  public void cancelLoads() {
    for (Load load : loads) {
      load.token.cancel();
    }
    loads.clear();
    stopShowing();
  }

  /**
   * Cancels the running loads; no result reaches the listener afterwards. Called on the UI thread.
   */
  public void close() {
    closed = true;
    ui.cancelTimer(showLoads);
    for (CancellationToken token : running) {
      token.cancel();
    }
    for (CancellationToken token : waiting) {
      if (waiting.remove(token)) {
        end(token);
      }
    }
  }

  /**
   * Counts the loads whose worker has not ended.
   *
   * @return the count
   */
  public int running() {
    return running.size();
  }

  /** A loaded file on its way to the UI thread, holding its reservation. */
  private record Loaded(LoadedFile file, DisplayProjection.Layout layout, long reserved) {}

  /** Posts the failure of an open that never reached a read. */
  private void postFailed(final Path path, final IOException error) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closed) {
              listener.failed(path, error);
            }
          }
        });
  }

  /**
   * Posts a result; a loaded file dropped here or on the UI thread releases its reservation. No
   * result and no error reports a cancel.
   */
  private void post(final Load load, final Loaded loaded, final IOException error) {
    if (closed) {
      if (loaded != null) {
        budget.release(loaded.reserved());
      }
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (closed) {
              if (loaded != null) {
                budget.release(loaded.reserved());
              }
              return;
            }
            finished(load);
            if ((loaded == null && error == null) || load.token.isCanceled()) {
              if (loaded != null) {
                budget.release(loaded.reserved());
              }
              listener.canceled(load.path);
            } else if (error != null) {
              listener.failed(load.path, error);
            } else {
              listener.opened(load.path, loaded.file(), loaded.layout(), loaded.reserved());
            }
          }
        });
  }

  private void postTooLarge(final Load load, final LoadCost cost, final long available) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (closed) {
              return;
            }
            finished(load);
            if (load.token.isCanceled()) {
              listener.canceled(load.path);
            } else {
              listener.tooLarge(load.path, cost, available, budget.maxHeap());
            }
          }
        });
  }
}
