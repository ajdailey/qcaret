package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Window close and quit for a {@link WindowSet}: the close of one window, the quit that asks
 * ({@link QuitPrompts}), the snapshot quit ({@link SnapshotQuit}), the quit itself and its quit and
 * finish listeners. The contracts are on the {@link WindowSet} methods that delegate here. Confined
 * to the UI thread, except {@link #isQuitting} and {@link #addFinishListener}.
 */
final class QuitSequence {
  private static final Logger LOG = LoggerFactory.getLogger(QuitSequence.class);

  private final WindowSet set;
  private final SessionController session;
  private final RecoveryController recovery;
  private final Settings settings;
  private final Workers workers;
  private final UiDispatcher ui;
  private final SnapshotQuit snapshot;
  private final List<Runnable> quitListeners = new CopyOnWriteArrayList<Runnable>();
  private final List<Runnable> finishListeners = new CopyOnWriteArrayList<Runnable>();
  private final AtomicBoolean finished = new AtomicBoolean();
  private QuitPrompts prompts;
  private volatile boolean quitting;
  private boolean quitAfterRestore;

  /**
   * Creates the sequence, not started.
   *
   * @param set the windows
   * @param session the session the quit captures and writes
   * @param recovery the recovery files the quit finishes
   * @param copies the copies a snapshot quit waits for
   * @param settings flushed by the quit
   * @param workers closed by the quit
   * @param ui the route onto the UI thread
   */
  QuitSequence(
      WindowSet set,
      SessionController session,
      RecoveryController recovery,
      RunningCopies copies,
      Settings settings,
      Workers workers,
      UiDispatcher ui) {
    this.set = set;
    this.session = session;
    this.recovery = recovery;
    this.settings = settings;
    this.workers = workers;
    this.ui = ui;
    this.snapshot = new SnapshotQuit(set, recovery, copies);
  }

  /** See {@link WindowSet#isQuitting}. */
  boolean isQuitting() {
    return quitting;
  }

  /** See {@link WindowSet#quitRunning}. */
  boolean quitRunning() {
    return quitting || snapshot.running() || prompts != null;
  }

  /** See {@link WindowSet#addQuitListener}. */
  void addQuitListener(Runnable listener) {
    quitListeners.add(listener);
  }

  private void runQuitListeners() {
    for (Runnable listener : quitListeners) {
      listener.run();
    }
  }

  /** See {@link WindowSet#addFinishListener}. */
  void addFinishListener(Runnable listener) {
    finishListeners.add(listener);
    if (finished.get() && finishListeners.remove(listener)) {
      listener.run();
    }
  }

  private void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    for (Runnable listener : finishListeners) {
      if (finishListeners.remove(listener)) {
        listener.run();
      }
    }
  }

  /** See {@link WindowSet#requestClose}. */
  void requestClose(final ApplicationController window) {
    if (quitRunning() || !set.windows().contains(window)) {
      return;
    }
    if (set.windows().size() == 1) {
      requestQuit();
      return;
    }
    if (session.restoring()) {
      return;
    }
    window
        .documents()
        .closeAll(
            new Runnable() {
              @Override
              public void run() {
                close(window);
              }
            });
  }

  private void close(final ApplicationController window) {
    if (quitting || !set.windows().contains(window)) {
      return;
    }
    if (set.windows().size() == 1) {
      requestQuit();
      return;
    }
    set.remove(window);
    window.rememberWindow();
    window.stop();
    window.hide();
    session.changed();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            window.disposeView();
          }
        });
  }

  /** See {@link WindowSet#requestQuit}. */
  void requestQuit() {
    if (quitting || snapshot.running() || prompts != null) {
      return;
    }
    if (session.owner() && settings.current().options().sessionSnapshot()) {
      if (session.restoring()) {
        quitAfterRestore = true;
      } else {
        snapshot.start();
      }
    } else {
      closeAndQuit();
    }
  }

  /** See {@link WindowSet#restoreEnded}. */
  void restoreEnded() {
    if (quitAfterRestore) {
      quitAfterRestore = false;
      requestQuit();
    }
  }

  /** See {@link WindowSet#closeAndQuit}. */
  void closeAndQuit() {
    if (quitting) {
      return;
    }
    prompts =
        new QuitPrompts(
            set,
            session,
            new Runnable() {
              @Override
              public void run() {
                prompts = null;
              }
            });
    prompts.start();
  }

  /** See {@link WindowSet#quit}. */
  void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    set.quitStarted();
    ApplicationController last = set.target();
    final SessionController.Capture capture = session.close(snapshot.written());
    recovery.quitting(snapshot.written() ? List.of() : set.dirtyBuffers());
    final List<ApplicationController> closing = new ArrayList<ApplicationController>(set.windows());
    for (ApplicationController window : closing) {
      window.stop();
    }
    if (last != null) {
      last.rememberWindow();
    }
    set.removeAll();
    for (ApplicationController window : closing) {
      window.hide();
    }
    Runnable end =
        new Runnable() {
          @Override
          public void run() {
            settings.flush();
            if (capture != null) {
              session.write(capture);
            }
            recovery.finish();
            runQuitListeners();
            if (!workers.close(Workers.CLOSE_BOUND)) {
              LOG.warn(
                  "Worker threads still running after {}: {}",
                  Workers.CLOSE_BOUND,
                  LogText.oneLine(workers.liveThreadNames()));
            }
            finish();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    disposeViews(closing);
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, end)) {
      runQuitListeners();
      finish();
      disposeViews(closing);
    }
  }

  private static void disposeViews(List<ApplicationController> closing) {
    for (ApplicationController window : closing) {
      window.disposeView();
    }
  }
}
