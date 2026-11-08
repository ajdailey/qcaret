package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Computes one editor view's modified-line marks ({@link LineChanges}) while the view shows them. A
 * text change waits {@value #DELAY_MILLIS} ms, so a burst of edits computes once; a save computes
 * at once, and a text that is the saved text clears the marks without a worker. The diff runs on a
 * {@code qcaret-cpu-} worker from the saved and current snapshots, a newer request cancels it, and
 * a result shows only while the document still has that revision and saved text. Hiding the marks
 * cancels the computation and clears them. Confined to the UI thread.
 */
final class LineChangesUpdater implements DocumentListener {
  /** Wait after a text change before computing. */
  static final int DELAY_MILLIS = 250;

  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Supplier<EditorView> view;
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          compute();
        }
      };
  private boolean shown;
  private boolean disposed;
  private CancellationToken running;
  private LineChanges changes = LineChanges.NONE;

  LineChangesUpdater(
      Document document, Workers workers, UiDispatcher ui, Supplier<EditorView> view) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    document.addListener(this);
  }

  /** Returns the marks shown last; {@link LineChanges#NONE} while hidden. */
  LineChanges changes() {
    return changes;
  }

  /** Shows the marks, computing them now, or hides and clears them. */
  void setShown(boolean value) {
    if (value == shown || disposed) {
      return;
    }
    shown = value;
    if (shown) {
      compute();
    } else {
      ui.cancelTimer(timer);
      cancel();
      show(LineChanges.NONE);
    }
  }

  /** Gives a newly attached view the marks. */
  void viewAttached() {
    if (shown) {
      EditorView v = view.get();
      if (v != null) {
        v.showLineChanges(changes);
      }
      compute();
    }
  }

  /** Stops following the document and drops a running computation. */
  void dispose() {
    disposed = true;
    ui.cancelTimer(timer);
    cancel();
    document.removeListener(this);
  }

  @Override
  public void textChanged(DocumentChange change) {
    if (shown && !disposed) {
      ui.cancelTimer(timer);
      ui.timerExec(DELAY_MILLIS, timer);
    }
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // The marks follow the texts.
  }

  @Override
  public void textSaved() {
    if (shown && !disposed) {
      ui.cancelTimer(timer);
      compute();
    }
  }

  private void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  private void compute() {
    if (disposed || !shown || view.get() == null) {
      return;
    }
    cancel();
    final TextSnapshot saved = document.savedText();
    final TextSnapshot current = document.text();
    final long revision = document.revision();
    if (saved == current) {
      show(new LineChanges(revision, new byte[0], false));
      return;
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final LineChanges computed;
              try {
                computed = LineChanges.compute(saved, current, revision, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      computed(token, saved, computed);
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  private void computed(CancellationToken token, TextSnapshot saved, LineChanges computed) {
    if (token != running || disposed) {
      return;
    }
    running = null;
    // A text change or save since then has its own request.
    if (document.revision() != computed.revision() || document.savedText() != saved) {
      return;
    }
    show(computed);
  }

  private void show(LineChanges value) {
    changes = value;
    EditorView v = view.get();
    if (v != null) {
      v.showLineChanges(value);
    }
  }
}
