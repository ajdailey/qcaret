package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The find bar's Replace All: finds every match and builds the edit on a {@code qcaret-cpu-}
 * worker, then applies it on the UI thread as one undo step to the document it started on,
 * whichever tab is selected by then. Up to {@value #SEPARATE_EDITS} matches apply as one
 * replacement each; more apply as one replacement from the first match to the end of the last, as
 * Sort Lines does, because {@code Document.apply} costs a rope read and a change record per
 * replacement on the UI thread. A newer Replace All, closing the document and {@link #cancel}
 * cancel the run; a result for an older revision is dropped with a notice. Confined to the UI
 * thread.
 */
final class ReplaceAllRun {
  /** Most matches applied as separate replacements, which keep bookmarks and folds between them. */
  static final int SEPARATE_EDITS = 10_000;

  /** What a finished run reports. */
  interface Outcome {
    /**
     * Called on the UI thread when a run ends without a newer one.
     *
     * @param notice the bar's notice, or null for none
     * @param error true when the notice is a failure
     */
    void ended(String notice, boolean error);
  }

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Outcome outcome;
  private CancellationToken running;
  private Document document;

  /** Runs on the worker before the search; a test seam, null otherwise. */
  private volatile Runnable beforeWork;

  ReplaceAllRun(DocumentController documents, Workers workers, UiDispatcher ui, Outcome outcome) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.outcome = outcome;
  }

  /** Reports whether a run computes on a worker. */
  boolean running() {
    return running != null;
  }

  void setBeforeWork(Runnable hook) {
    beforeWork = hook;
  }

  /**
   * Cancels the running one and replaces every match of a query in an editor's document.
   *
   * @param editor the view whose caret maps through the replacements
   * @param matcher the query
   * @param template the replacement
   */
  void start(
      final EditorController editor, final TextMatcher matcher, final ReplaceTemplate template) {
    cancel();
    final Document target = editor.document();
    target.clearPreedit();
    final TextSnapshot snapshot = target.text();
    final long revision = target.revision();
    final String ending = target.lineEnding().text();
    final int caret = editor.selections().selections().primary().caret();
    final CancellationToken token = new CancellationToken();
    running = token;
    document = target;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Result result = null;
              String failure = UiText.FIND_FAILED.text();
              boolean canceled = false;
              try {
                Runnable hook = beforeWork;
                if (hook != null) {
                  hook.run();
                }
                result = compute(snapshot, matcher, template, ending, caret, token);
                failure = null;
              } catch (CancellationException e) {
                canceled = true;
              } catch (PatternTooComplexException e) {
                failure = UiText.FIND_TOO_COMPLEX.text();
              } finally {
                if (!canceled) {
                  post(token, editor, revision, result, failure);
                }
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
      document = null;
    }
  }

  /** The views changed: cancels the run when its document closed. */
  void viewsChanged() {
    if (running != null && !documents.isOpen(document)) {
      cancel();
    }
  }

  /** Cancels the running one. */
  void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
      document = null;
    }
  }

  /**
   * A computed run.
   *
   * @param batch the edit, empty when nothing matched
   * @param count the matches replaced
   * @param caret where the primary caret lands
   */
  private record Result(EditBatch batch, int count, int caret) {}

  @WorkerThread
  private static Result compute(
      TextSnapshot snapshot,
      TextMatcher matcher,
      ReplaceTemplate template,
      String ending,
      int caret,
      CancellationToken token) {
    List<Replacement> found = matcher.replaceAll(snapshot, template, token);
    List<Replacement> replacements = new ArrayList<Replacement>(found.size());
    for (Replacement r : found) {
      replacements.add(
          new Replacement(
              r.offset(), r.removeLength(), EditorController.convertBreaks(r.text(), ending)));
    }
    int mapped = FindController.mapped(caret, replacements);
    List<Replacement> edit =
        replacements.size() <= SEPARATE_EDITS
            ? replacements
            : List.of(joined(snapshot, replacements, token));
    return new Result(EditBatch.of(EditBatch.Kind.COMMAND, edit), replacements.size(), mapped);
  }

  /**
   * Returns one replacement equal to a list of them: from the first one's offset to the last one's
   * end, with the text between them copied.
   *
   * @param text the text the replacements apply to
   * @param replacements at least one, by offset, not overlapping
   * @param token checked between replacements
   * @return the replacement
   * @throws CancellationException when canceled
   */
  @WorkerThread
  static Replacement joined(
      TextContent text, List<Replacement> replacements, CancellationToken token) {
    int start = replacements.get(0).offset();
    int end = replacements.get(replacements.size() - 1).end();
    long size = end - start;
    for (Replacement r : replacements) {
      size += r.text().length() - r.removeLength();
    }
    final StringBuilder out = new StringBuilder((int) Math.min(size, Integer.MAX_VALUE - 8));
    ChunkVisitor copy =
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int from, int to) {
            out.append(chunk, from, to);
          }
        };
    int at = start;
    for (int i = 0; i < replacements.size(); i++) {
      if ((i & 0xfff) == 0) {
        token.check();
      }
      Replacement r = replacements.get(i);
      if (r.offset() > at) {
        text.readChunks(at, r.offset(), copy);
      }
      out.append(r.text());
      at = r.end();
    }
    return new Replacement(start, end - start, out.toString());
  }

  @WorkerThread
  private void post(
      final CancellationToken token,
      final EditorController editor,
      final long revision,
      final Result result,
      final String failure) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (token != running) {
              return;
            }
            Document target = document;
            running = null;
            document = null;
            if (failure != null) {
              outcome.ended(failure, true);
            } else {
              apply(target, editor, revision, result);
            }
          }
        });
  }

  private void apply(Document target, EditorController editor, long revision, Result result) {
    if (!documents.isOpen(target)) {
      outcome.ended(null, false);
      return;
    }
    if (target.revision() != revision) {
      outcome.ended(UiText.FIND_REPLACE_ALL_STALE.text(), true);
      return;
    }
    if (target.isReadOnly()) {
      outcome.ended(null, false);
      return;
    }
    if (result.count() == 0) {
      outcome.ended(UiText.FIND_NOT_FOUND.text(), true);
      return;
    }
    EditorController origin = editor.disposed() ? documents.editorOf(target) : editor;
    target.apply(
        result.batch(),
        origin.selections(),
        origin == editor ? SelectionSet.caret(result.caret()) : null);
    if (origin == documents.activeEditor() && origin.view() != null) {
      origin.view().showCaret();
    }
    outcome.ended(
        UiText.FIND_REPLACED.format(
            UiText.count(result.count(), UiText.COUNT_MATCH, UiText.COUNT_MATCHES)),
        false);
  }
}
