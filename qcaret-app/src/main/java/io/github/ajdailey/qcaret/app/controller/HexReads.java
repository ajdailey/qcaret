package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.HexBlocks;
import io.github.ajdailey.qcaret.core.hex.HexSourceChangedException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The block reads of one hex view, on a worker without the busy cursor: a {@code qcaret-io-} thread
 * for a file, the CPU pool for a document's encoded text. One read runs at a time; a range asked
 * for meanwhile waits, a newer one replacing it, and starts when the running read ends. {@link
 * #reset} drops every read started before it. After a failure no read starts until the next reset.
 * Confined to the UI thread.
 */
final class HexReads {
  /** Receives the reads' ends on the UI thread. */
  interface Listener {
    /** A read landed in the cache. */
    void loaded();

    /** The file changed on disk. */
    void changed();

    /** A read failed. */
    void failed(IOException error);
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final boolean fileReads;
  private final Listener listener;
  private HexBlocks blocks;
  private long generation;
  private CancellationToken reading;
  private long wantedStart;
  private int wantedLength;
  private boolean failed;

  HexReads(Workers workers, UiDispatcher ui, boolean fileReads, Listener listener) {
    this.workers = workers;
    this.ui = ui;
    this.fileReads = fileReads;
    this.listener = listener;
  }

  /** Reads into another cache from now on; reads started before are dropped. */
  void reset(HexBlocks next) {
    cancel();
    blocks = next;
    failed = false;
  }

  /** Drops every running and started read. */
  void cancel() {
    generation++;
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
  }

  /**
   * Reads a range unless it is cached.
   *
   * @param start the first byte
   * @param length the byte count
   */
  void request(long start, int length) {
    if (failed || blocks == null || length <= 0 || blocks.cached(start, length)) {
      return;
    }
    wantedStart = start;
    wantedLength = length;
    if (reading == null) {
      read();
    }
  }

  private void read() {
    final HexBlocks cache = blocks;
    final long start = wantedStart;
    final int length = wantedLength;
    final long started = generation;
    final CancellationToken token = new CancellationToken();
    reading = token;
    Runnable task =
        new Runnable() {
          @Override
          public void run() {
            IOException error = null;
            try {
              cache.load(start, length, token);
            } catch (IOException e) {
              error = e;
            } catch (CancellationException e) {
              // A reset or a dispose canceled it.
            }
            final IOException failure = error;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    ended(token, started, failure);
                  }
                });
          }
        };
    boolean running;
    if (fileReads) {
      running = workers.tryStart(Workers.Kind.IO, task);
    } else {
      try {
        workers.executeCpu(task);
        running = true;
      } catch (RejectedExecutionException e) {
        running = false;
      }
    }
    if (!running) {
      reading = null;
    }
  }

  private void ended(CancellationToken token, long started, IOException error) {
    if (reading == token) {
      reading = null;
    }
    if (started != generation) {
      return;
    }
    if (error != null) {
      failed = true;
      if (error instanceof HexSourceChangedException) {
        listener.changed();
      } else {
        listener.failed(error);
      }
      return;
    }
    listener.loaded();
  }
}
