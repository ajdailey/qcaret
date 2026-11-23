package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.format.FormatError;
import io.github.ajdailey.qcaret.core.format.FormatLayout;
import io.github.ajdailey.qcaret.core.format.FormatResult;
import io.github.ajdailey.qcaret.core.format.Formats;
import java.util.concurrent.CancellationException;

/**
 * One view's Edit > Format XML and JSON runs ({@link Formats}): each computes on a {@code
 * qcaret-cpu-} worker and applies on the UI thread as one undo step, with the editor's busy cursor
 * ({@link EditorController#busyCursor}) from the command to the apply. A newer run or Esc cancels
 * the running one; a result for an older revision is dropped with a notice. A failure changes
 * nothing, names the format, line, column and reason in a notice, marks the token there ({@link
 * FormatErrorMark}) and moves the caret to it; the column is the status bar's. A Pretty Print or
 * Minify that changes nothing says why ({@link FormatUnchanged}). Pretty Print and Minify do
 * nothing on a read-only document; Check runs on any. Confined to the UI thread.
 */
final class FormatRuns {
  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private final FormatErrorMark mark;
  private CancellationToken work;

  FormatRuns(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
    this.mark = new FormatErrorMark(editor);
  }

  /**
   * Returns the error mark.
   *
   * @return the mark
   */
  FormatErrorMark mark() {
    return mark;
  }

  /** Cancels the running format and stops following the document. */
  void dispose() {
    cancel();
    mark.dispose();
  }

  /** Cancels the running format. */
  void cancel() {
    if (work != null) {
      work.cancel();
    }
  }

  /**
   * Starts a format command.
   *
   * @param command one of the Edit > Format XML and JSON commands
   * @param strictJson true to refuse JSON comments ({@code editor.strictJson})
   */
  void run(CommandId command, boolean strictJson) {
    final Formats.Language language = language(command);
    final Formats.Mode mode = mode(command);
    if (mode != Formats.Mode.CHECK && document.isReadOnly()) {
      return;
    }
    final TextSnapshot text = document.text();
    final SelectionSet set = selections.selections();
    final long revision = document.revision();
    if (work != null) {
      work.cancel();
    }
    final CancellationToken token = new CancellationToken();
    work = token;
    String indent = document.insertSpaces() ? " ".repeat(document.tabWidth()) : "\t";
    final FormatLayout layout =
        new FormatLayout(indent, document.lineEnding().text(), strictJson, maxChars());
    boolean started =
        new BusyWork(ui, editor.busyCursor()) {
          private FormatResult result;

          @Override
          @WorkerThread
          void work() {
            try {
              result = Formats.run(language, mode, text, set, layout, token);
            } catch (CancellationException e) {
              // Esc, a newer run, or the view closed.
            }
          }

          @Override
          void finished(boolean completed) {
            computed(token, language, mode, revision, set, result);
          }
        }.startCpu(workers);
    if (!started) {
      work = null;
    }
  }

  /** Returns the longest output of one range: an eighth of the maximum heap, in chars. */
  private static int maxChars() {
    return (int) Math.min(Integer.MAX_VALUE - 16, Runtime.getRuntime().maxMemory() / 8);
  }

  private void computed(
      CancellationToken token,
      Formats.Language language,
      Formats.Mode mode,
      long revision,
      SelectionSet set,
      FormatResult result) {
    if (token != work) {
      return;
    }
    work = null;
    if (editor.disposed() || result == null || token.isCanceled()) {
      return;
    }
    if (document.revision() != revision) {
      editor.notice(UiText.FORMAT_STALE.text());
      return;
    }
    String name = language.name();
    FormatError error = result.error();
    if (error != null) {
      showError(name, error);
      return;
    }
    mark.clear();
    if (mode == Formats.Mode.CHECK) {
      String notice;
      if (result.comments()) {
        notice = UiText.FORMAT_WELL_FORMED_COMMENTS.text();
      } else if (result.fragment()) {
        notice = UiText.FORMAT_WELL_FORMED_FRAGMENT.text();
      } else {
        notice = UiText.FORMAT_WELL_FORMED.format(name);
      }
      editor.notice(notice);
    } else if (result.batch() != null && !document.isReadOnly()) {
      document.clearPreedit();
      boolean moved = !set.equals(selections.selections());
      editor.applyEdit(new Edit(result.batch(), moved ? null : result.after()));
    } else if (result.batch() == null) {
      editor.notice(FormatUnchanged.notice(mode, result.kept()));
    }
  }

  private void showError(String name, FormatError error) {
    editor.notice(errorNotice(document, name, error));
    mark.set(error.offset());
    selections.set(SelectionSet.caret(error.offset()));
    editor.showCaret();
  }

  /**
   * Returns the notice for a format error, with the status bar's column.
   *
   * @param document the formatted document, unchanged since the run
   * @param name the format's name
   * @param error the error
   * @return the notice
   */
  static String errorNotice(Document document, String name, FormatError error) {
    TextSnapshot text = document.text();
    int line = text.lineAt(error.offset());
    LineView view = text.lineView(line, new LineView());
    int index = Math.min(error.offset() - view.start(), view.length());
    int column = Columns.column(view, index, document.tabWidth());
    return UiText.FORMAT_ERROR.format(name, line + 1, column + 1, error.reason());
  }

  private static Formats.Language language(CommandId command) {
    return switch (command) {
      case PRETTY_PRINT_XML, MINIFY_XML, CHECK_XML -> Formats.Language.XML;
      default -> Formats.Language.JSON;
    };
  }

  private static Formats.Mode mode(CommandId command) {
    return switch (command) {
      case PRETTY_PRINT_XML, PRETTY_PRINT_JSON -> Formats.Mode.PRETTY_PRINT;
      case MINIFY_XML, MINIFY_JSON -> Formats.Mode.MINIFY;
      default -> Formats.Mode.CHECK;
    };
  }
}
