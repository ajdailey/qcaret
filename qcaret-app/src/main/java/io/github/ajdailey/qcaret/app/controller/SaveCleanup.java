package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineTransforms;
import io.github.ajdailey.qcaret.core.text.FileSettings;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * The edit a save makes before it writes, from {@code trim_trailing_whitespace} and {@code
 * insert_final_newline}: spaces and tabs at each line's end removed, then the document's line
 * ending appended to a text that does not end with a break. A {@code false} or missing property
 * changes nothing. The controller applies the edit as one undo step and saves the result.
 */
final class SaveCleanup {
  private SaveCleanup() {}

  /**
   * Computes the edit on a {@code qcaret-io-} worker and passes it to {@code done} on the UI
   * thread: null when the text needs none or the token was canceled. The activity counts one
   * operation until {@code done} has run, so the write {@code done} starts keeps the view busy.
   *
   * @param workers starts the task
   * @param ui runs {@code done}
   * @param activity counts the running task
   * @param text the snapshot to save
   * @param settings the settings it saves with
   * @param ending the break a final newline appends
   * @param token canceled by Esc or a close
   * @param done receives the edit
   * @return false when the workers refused the task
   */
  static boolean start(
      Workers workers,
      final UiDispatcher ui,
      final ActivityIndicator activity,
      final TextContent text,
      final FileSettings settings,
      final LineEnding ending,
      final CancellationToken token,
      final Consumer<EditBatch> done) {
    activity.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                EditBatch edit = null;
                try {
                  edit = edits(text, settings, ending, token);
                } catch (CancellationException e) {
                  // Esc or a close: done finds the save ended.
                } finally {
                  final EditBatch cleanup = edit;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          try {
                            done.accept(cleanup);
                          } finally {
                            activity.setIdle();
                          }
                        }
                      });
                }
              }
            });
    if (!started) {
      activity.setIdle();
    }
    return started;
  }

  /**
   * Reports whether a save with these settings may edit the text.
   *
   * @param settings the document's settings
   * @return true when either property is true
   */
  static boolean wanted(FileSettings settings) {
    return settings.trimTrailing() || settings.finalNewline();
  }

  /**
   * Computes the edit.
   *
   * @param text the text to save
   * @param settings the document's settings
   * @param ending the break a final newline appends
   * @param token checked as {@link LineTransforms#trimTrailing} reads
   * @return the edit in the text's coordinates, or null when the text needs none
   * @throws java.util.concurrent.CancellationException when canceled
   */
  static EditBatch edits(
      TextContent text, FileSettings settings, LineEnding ending, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    if (settings.trimTrailing()) {
      Edit trim = LineTransforms.trimTrailing(text, SelectionSet.caret(0), token);
      if (trim != null) {
        out.addAll(trim.batch().replacements());
      }
    }
    int length = text.length();
    if (settings.finalNewline()) {
      Replacement tail = out.isEmpty() ? null : out.get(out.size() - 1);
      boolean trimsEnd = tail != null && tail.offset() + tail.removeLength() == length;
      int end = trimsEnd ? tail.offset() : length;
      if (end > 0 && text.charAt(end - 1) != '\n' && text.charAt(end - 1) != '\r') {
        if (trimsEnd) {
          out.set(
              out.size() - 1, new Replacement(tail.offset(), tail.removeLength(), ending.text()));
        } else {
          out.add(new Replacement(length, 0, ending.text()));
        }
      }
    }
    return out.isEmpty() ? null : EditBatch.of(EditBatch.Kind.COMMAND, out);
  }
}
