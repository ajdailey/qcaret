package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Save with an encoding and the line-ending conversion of one {@link DocumentController}'s
 * documents. A conversion computes the new text on a {@code qcaret-cpu-} worker under the
 * document's {@code converting} token; a newer conversion or the close cancels it, and a result for
 * a changed, read-only or closed document is dropped. Confined to the UI thread.
 */
final class DocumentEncodings {
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;

  DocumentEncodings(Workers workers, UiDispatcher ui, MainView view) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /** {@link DocumentController#saveWithEncoding} for a document. */
  void saveWith(OpenDocument d, Charset charset) {
    if (d.binary) {
      return;
    }
    d.file.forced = charset;
    TextEncoding current = d.document.encoding();
    if (!current.charset().equals(charset)) {
      d.document.setEncoding(TextEncoding.of(charset, false));
      d.updateTitle();
      view.documentStateChanged();
    }
  }

  /** {@link DocumentController#convertLineEndings} for a document. */
  void convert(final OpenDocument d, final LineEnding ending) {
    if (d.document.isReadOnly()) {
      return;
    }
    if (!d.document.lineEndings().isMixed()
        && d.document.lineEndings().dominant(ending) == ending) {
      d.document.setLineEnding(ending);
      view.documentStateChanged();
      return;
    }
    if (d.converting != null) {
      d.converting.cancel();
    }
    final CancellationToken token = new CancellationToken();
    d.converting = token;
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    final long revision = d.document.revision();
    final int caret = d.editor.selections().selections().primary().caret();
    boolean started =
        new BusyWork(ui, busyOver(d)) {
          private LineConversion converted;

          @Override
          @WorkerThread
          void work() {
            try {
              converted = LineConversion.convert(snapshot.text(), ending, caret, token);
            } catch (CancellationException e) {
              // Closed or replaced by a newer conversion.
            }
          }

          @Override
          void finished(boolean completed) {
            converted(d, token, revision, ending, converted);
          }
        }.startCpu(workers);
    if (!started) {
      d.converting = null;
    }
  }

  /** Returns the window's busy cursor joined with those of the document's views at the call. */
  private ActivityIndicator busyOver(OpenDocument d) {
    final List<ActivityIndicator> shown = new ArrayList<ActivityIndicator>();
    shown.add(view);
    for (EditorController e : d.editors) {
      shown.add(e.busyCursor());
    }
    return new ActivityIndicator() {
      @Override
      public void setActive() {
        for (ActivityIndicator a : shown) {
          a.setActive();
        }
      }

      @Override
      public void setIdle() {
        for (ActivityIndicator a : shown) {
          a.setIdle();
        }
      }
    };
  }

  private void converted(
      OpenDocument d,
      CancellationToken token,
      long revision,
      LineEnding ending,
      LineConversion result) {
    if (d.converting != token) {
      return;
    }
    d.converting = null;
    if (result == null
        || d.closed
        || d.document.isReadOnly()
        || d.document.revision() != revision) {
      return;
    }
    d.document.apply(
        EditBatch.of(EditBatch.Kind.COMMAND, List.of(result.replacement())),
        d.editor.selections(),
        SelectionSet.caret(result.caret()));
    d.document.setLineEnding(ending);
    d.tab.showCaret();
    view.documentStateChanged();
  }
}
