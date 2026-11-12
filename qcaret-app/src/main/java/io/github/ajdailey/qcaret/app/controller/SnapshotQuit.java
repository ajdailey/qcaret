package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The quit with {@code session.snapshot} on. Writes every window's dirty buffers as a snapshot,
 * again while they differ from those written, waits for {@link RunningCopies}, then runs {@link
 * WindowSet#quit}. A failed write shows a notice and falls back to the quit that asks; a failed
 * copy stops the quit. Confined to the UI thread.
 */
final class SnapshotQuit {
  private final WindowSet set;
  private final RecoveryController recovery;
  private final RunningCopies copies;
  private boolean running;
  private boolean written;

  /**
   * Creates the quit, not started.
   *
   * @param set the windows
   * @param recovery writes the snapshot
   * @param copies the copies the quit waits for
   */
  SnapshotQuit(WindowSet set, RecoveryController recovery, RunningCopies copies) {
    this.set = set;
    this.recovery = recovery;
    this.copies = copies;
  }

  /** Reports whether a snapshot write or the copy wait runs. */
  boolean running() {
    return running;
  }

  /** Reports whether the snapshot holds the dirty buffers the quit leaves. */
  boolean written() {
    return written;
  }

  /** Writes the dirty buffers, then quits as described above. */
  void start() {
    running = true;
    final List<DocumentController.Buffer> buffers = set.dirtyBuffers();
    recovery.writeSnapshot(
        buffers,
        new Consumer<Boolean>() {
          @Override
          public void accept(Boolean ok) {
            ended(buffers, ok);
          }
        });
  }

  private void ended(final List<DocumentController.Buffer> buffers, boolean ok) {
    running = false;
    if (set.isQuitting()) {
      return;
    }
    if (!ok) {
      ApplicationController window = set.target();
      if (window != null) {
        window.showNotice(UiText.SESSION_SNAPSHOT_FAILED.text());
      }
      set.closeAndQuit();
    } else if (!unchanged(buffers, set.dirtyBuffers())) {
      start();
    } else if (copies.any()) {
      running = true;
      copies.await(
          new Runnable() {
            @Override
            public void run() {
              ended(buffers, true);
            }
          },
          new Runnable() {
            @Override
            public void run() {
              running = false;
            }
          });
    } else {
      written = true;
      set.quit();
    }
  }

  /**
   * Reports whether the dirty buffers are those written, matched by buffer id, at their revision.
   */
  private static boolean unchanged(
      List<DocumentController.Buffer> written, List<DocumentController.Buffer> now) {
    if (written.size() != now.size()) {
      return false;
    }
    Map<String, Long> revisions = new HashMap<String, Long>();
    for (DocumentController.Buffer buffer : written) {
      revisions.put(buffer.id(), buffer.snapshot().revision());
    }
    for (DocumentController.Buffer buffer : now) {
      Long revision = revisions.get(buffer.id());
      if (revision == null || revision.longValue() != buffer.snapshot().revision()) {
        return false;
      }
    }
    return true;
  }
}
