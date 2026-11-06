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
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens files for one window. A {@code qcaret-io-} worker resolves the path to its real path, the
 * UI thread offers it to {@link Listener#resolved} (an open document ends the open there), and a
 * second worker reads the file; every result reaches the {@link Listener} on the UI thread. Before
 * reading, the worker estimates the load's heap cost ({@link DocumentFiles#estimate}) and reserves
 * it from the {@link MemoryBudget}; a load the budget cannot hold is refused unread. A loaded
 * document arrives with its rows ({@link DisplayProjection#layout}) and its reservation lowered to
 * the retained cost, which the listener owns from then on. {@link #close} cancels the running
 * loads; a load that finishes after it, on the worker or in a task already posted, drops its
 * document and releases its reservation, so no document reaches a closed window. Each open counts
 * as one {@link ActivityIndicator} operation until it ends: unread after {@link Listener#resolved},
 * refused, failed, loaded or closed.
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
     * A load was refused before reading: the budget cannot hold it.
     *
     * @param path the validated path
     * @param cost the estimate
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
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final ActivityIndicator activity;
  private final Listener listener;
  private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
  private final Set<CancellationToken> waiting = ConcurrentHashMap.newKeySet();
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
                  post(path, null, e);
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
  private boolean load(final Path file, final LoadOptions options, final CancellationToken token) {
    return workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            long reserved = 0;
            try {
              if (closed) {
                return;
              }
              LoadCost cost = DocumentFiles.estimate(file, options.limits());
              if (!budget.reserve(cost.reserveBytes())) {
                postTooLarge(file, cost, budget.available());
                return;
              }
              reserved = cost.reserveBytes();
              LoadedFile loaded = DocumentFiles.load(file, options, token);
              DisplayProjection.Layout layout =
                  DisplayProjection.layout(loaded.document().text(), 0, token);
              budget.release(reserved - cost.retainedBytes());
              reserved = cost.retainedBytes();
              post(file, new Loaded(loaded, layout, reserved), null);
              reserved = 0;
            } catch (IOException e) {
              post(file, null, e);
            } catch (CancellationException e) {
              // Closed: nothing to report.
            } finally {
              budget.release(reserved);
              end(token);
            }
          }
        });
  }

  private void end(CancellationToken token) {
    running.remove(token);
    activity.setIdle();
  }

  /**
   * Cancels the running loads; no result reaches the listener afterwards. Called on the UI thread.
   */
  public void close() {
    closed = true;
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

  /** Posts a result; a loaded file dropped here or on the UI thread releases its reservation. */
  private void post(final Path path, final Loaded loaded, final IOException error) {
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
            if (error != null) {
              listener.failed(path, error);
            } else {
              listener.opened(path, loaded.file(), loaded.layout(), loaded.reserved());
            }
          }
        });
  }

  private void postTooLarge(final Path path, final LoadCost cost, final long available) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!closed) {
              listener.tooLarge(path, cost, available, budget.maxHeap());
            }
          }
        });
  }
}
