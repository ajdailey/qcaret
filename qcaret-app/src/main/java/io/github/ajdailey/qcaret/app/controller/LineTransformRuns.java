package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineTransforms;
import io.github.ajdailey.qcaret.core.edit.ParagraphWrap;
import java.util.concurrent.CancellationException;

/**
 * One view's Sort, Remove Duplicate Lines, Trim Trailing Whitespace and Wrap Paragraph runs: each
 * computes its edit on a {@code qcaret-cpu-} worker and applies it on the UI thread, with the
 * editor's busy cursor ({@link EditorController#busyCursor}) from the command to the apply. A newer
 * run or Esc cancels the running one; a result for an older revision is dropped with a notice.
 * Confined to the UI thread.
 */
final class LineTransformRuns {
  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken lineWork;
  private int wrapColumn = 1;

  LineTransformRuns(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
  }

  /** Cancels the running transform. */
  void cancel() {
    if (lineWork != null) {
      lineWork.cancel();
    }
  }

  /**
   * Runs Wrap Paragraph ({@link ParagraphWrap}) with the grammar's line comment.
   *
   * @param column the widest line, in columns
   */
  void wrapParagraph(int column) {
    wrapColumn = column;
    transformLines(CommandId.WRAP_PARAGRAPH);
  }

  /**
   * Computes a sort, duplicate removal, trim or paragraph wrap on a worker and applies it at the
   * same revision.
   */
  void transformLines(final CommandId command) {
    if (document.isReadOnly()) {
      return;
    }
    final TextSnapshot text = document.text();
    final SelectionSet set = selections.selections();
    final long revision = document.revision();
    if (lineWork != null) {
      lineWork.cancel();
    }
    final CancellationToken token = new CancellationToken();
    lineWork = token;
    final String comment = editor.comments().line();
    final int tabWidth = document.tabWidth();
    final String ending = document.lineEnding().text();
    final int column = wrapColumn;
    boolean started =
        new BusyWork(ui, editor.busyCursor()) {
          private Edit edit;

          @Override
          @WorkerThread
          void work() {
            try {
              edit =
                  command == CommandId.WRAP_PARAGRAPH
                      ? ParagraphWrap.wrap(text, set, column, comment, tabWidth, ending, token)
                      : lineEdit(command, text, set, token);
            } catch (CancellationException e) {
              // Esc, a newer transform, or the view closed.
            }
          }

          @Override
          void finished(boolean completed) {
            linesComputed(token, revision, set, edit);
          }
        }.startCpu(workers);
    if (!started) {
      lineWork = null;
    }
  }

  /** Returns the edit of a line transform command, or null when it changes nothing. */
  @WorkerThread
  static Edit lineEdit(
      CommandId command, TextSnapshot text, SelectionSet set, CancellationToken token) {
    return switch (command) {
      case SORT_LINES_ASCENDING ->
          LineTransforms.sort(text, set, LineTransforms.Order.ASCENDING, token);
      case SORT_LINES_DESCENDING ->
          LineTransforms.sort(text, set, LineTransforms.Order.DESCENDING, token);
      case SORT_LINES_IGNORE_CASE ->
          LineTransforms.sort(text, set, LineTransforms.Order.IGNORE_CASE, token);
      case REMOVE_DUPLICATE_LINES -> LineTransforms.removeDuplicates(text, set, token);
      default -> LineTransforms.trimTrailing(text, set, token);
    };
  }

  private void linesComputed(CancellationToken token, long revision, SelectionSet set, Edit edit) {
    if (token != lineWork) {
      return;
    }
    lineWork = null;
    applyLines(revision, set, edit, token.isCanceled());
  }

  private void applyLines(long revision, SelectionSet set, Edit edit, boolean canceled) {
    if (editor.disposed() || edit == null || document.isReadOnly() || canceled) {
      return;
    }
    if (document.revision() != revision) {
      editor.notice(UiText.LINES_STALE.text());
      return;
    }
    document.clearPreedit();
    boolean moved = !set.equals(selections.selections());
    editor.applyEdit(moved ? new Edit(edit.batch(), null, edit.unchanged()) : edit);
  }
}
