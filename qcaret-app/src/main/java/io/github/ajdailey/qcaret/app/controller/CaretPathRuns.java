package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.format.CaretPath;
import io.github.ajdailey.qcaret.core.format.Formats;
import java.util.concurrent.CancellationException;

/**
 * One view's Edit > Copy XML Path and Copy JSON Path ({@link CaretPath}) at the primary caret:
 * computed on a {@code qcaret-cpu-} worker under the editor's busy cursor, then copied with a
 * notice naming the path. A newer run or Esc cancels the running one; a result for an older
 * revision is dropped with a notice. Malformed text before the caret gives the format commands'
 * error notice and copies nothing; the caret stays. Confined to the UI thread.
 */
final class CaretPathRuns {
  private final EditorController editor;
  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken work;

  CaretPathRuns(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.workers = workers;
    this.ui = ui;
  }

  /** Cancels the running search. */
  void cancel() {
    if (work != null) {
      work.cancel();
    }
  }

  /**
   * Starts Copy XML Path or Copy JSON Path.
   *
   * @param command {@link CommandId#COPY_XML_PATH} or {@link CommandId#COPY_JSON_PATH}
   * @param strictJson true to refuse JSON comments ({@code editor.strictJson})
   */
  void run(CommandId command, boolean strictJson) {
    final Formats.Language language =
        command == CommandId.COPY_XML_PATH ? Formats.Language.XML : Formats.Language.JSON;
    final TextSnapshot text = document.text();
    final int caret = editor.selections().selections().primary().caret();
    final long revision = document.revision();
    final boolean strict = strictJson;
    if (work != null) {
      work.cancel();
    }
    final CancellationToken token = new CancellationToken();
    work = token;
    boolean started =
        new BusyWork(ui, editor.busyCursor()) {
          private CaretPath.Result result;

          @Override
          @WorkerThread
          void work() {
            try {
              result = CaretPath.find(language, text, caret, strict, token);
            } catch (CancellationException e) {
              // Esc, a newer run, or the view closed.
            }
          }

          @Override
          void finished(boolean completed) {
            found(token, language, revision, result);
          }
        }.startCpu(workers);
    if (!started) {
      work = null;
    }
  }

  private void found(
      CancellationToken token, Formats.Language language, long revision, CaretPath.Result result) {
    if (token != work) {
      return;
    }
    work = null;
    if (editor.disposed() || result == null || token.isCanceled()) {
      return;
    }
    if (document.revision() != revision) {
      editor.macros().failed(UiText.PATH_STALE.text());
    } else if (result.error() != null) {
      editor.macros().failed(FormatRuns.errorNotice(document, language.name(), result.error()));
    } else {
      editor.copyText(result.path());
      editor.notice(UiText.PATH_COPIED.format(result.path()));
    }
  }
}
