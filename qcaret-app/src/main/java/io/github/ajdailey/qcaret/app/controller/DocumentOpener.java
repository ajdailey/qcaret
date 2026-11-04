package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens files for one window: each load runs on a {@code qcaret-io-} worker and its document
 * reaches the {@link Listener} on the UI thread. {@link #close} cancels the running loads; a load
 * that finishes after it, on the worker or in a task already posted, drops its document, so no
 * document reaches a closed window.
 */
public final class DocumentOpener {
  /** Receives load results on the UI thread. */
  public interface Listener {
    /**
     * A file loaded.
     *
     * @param path the validated path
     * @param file the document and what the load found
     */
    void opened(Path path, LoadedFile file);

    /**
     * A load failed; not called for a canceled load.
     *
     * @param path the path as requested
     * @param error the failure
     */
    void failed(Path path, IOException error);
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final Listener listener;
  private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  /**
   * Creates the opener.
   *
   * @param workers starts the loads
   * @param ui runs the results on the UI thread
   * @param listener receives the results
   */
  public DocumentOpener(Workers workers, UiDispatcher ui, Listener listener) {
    this.workers = workers;
    this.ui = ui;
    this.listener = listener;
  }

  /**
   * Starts a load; called on the UI thread. Does nothing once closed or once the workers are
   * closed.
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
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                try {
                  if (closed) {
                    return;
                  }
                  Path file = SafePaths.userFile(path);
                  LoadedFile loaded = DocumentFiles.load(file, options, token);
                  post(file, loaded, null);
                } catch (IOException e) {
                  post(path, null, e);
                } catch (CancellationException e) {
                  // Closed: nothing to report.
                } finally {
                  running.remove(token);
                }
              }
            });
    if (!started) {
      running.remove(token);
    }
  }

  /**
   * Cancels the running loads; no result reaches the listener afterwards. Called on the UI thread.
   */
  public void close() {
    closed = true;
    for (CancellationToken token : running) {
      token.cancel();
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

  private void post(final Path path, final LoadedFile loaded, final IOException error) {
    if (closed) {
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (closed) {
              return;
            }
            if (error != null) {
              listener.failed(path, error);
            } else {
              listener.opened(path, loaded);
            }
          }
        });
  }
}
