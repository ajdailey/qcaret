package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.Match;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.SearchQuery;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.PatternSyntaxException;

/**
 * A macro run's find, Replace and Replace All steps: the find bar's searches with the step's own
 * query and options, leaving the bar's text, options and history alone. A find never wraps, so no
 * match fails the step; Replace replaces the selection when it is a match, as the bar does, then
 * selects the next match, and fails when there is none. Each search runs on a {@code qcaret-cpu-}
 * worker; the result applies only while its operation is current and the document is at the
 * revision it read. The outcome is always posted, never reported inside the call. Confined to the
 * UI thread.
 */
final class MacroFind {
  /** Receives a step's outcome on the UI thread. */
  interface Outcome {
    /**
     * Called once the step ended, unless {@link #cancel} ran first.
     *
     * @param failure why the step failed, or null
     */
    void ended(String failure);
  }

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken running;
  private ReplaceAllRun replaceAll;

  MacroFind(DocumentController documents, Workers workers, UiDispatcher ui) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
  }

  /** Cancels the running step; its outcome is never reported. */
  void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
    if (replaceAll != null) {
      replaceAll.cancel();
      replaceAll = null;
    }
  }

  /**
   * Runs a find step in an editor.
   *
   * @param editor the editor
   * @param step the step
   * @param outcome receives the end
   */
  void find(EditorController editor, MacroStep.Find step, Outcome outcome) {
    TextMatcher matcher = compile(step.text(), step.options(), outcome);
    if (matcher == null) {
      return;
    }
    Selection primary = editor.selections().selections().primary();
    int from =
        switch (step.direction()) {
          case FORWARD -> primary.end();
          case BACKWARD, INCREMENTAL -> primary.start();
        };
    select(editor, matcher, step.direction() != MacroStep.Direction.BACKWARD, from, null, outcome);
  }

  /**
   * Runs a Replace or Replace All step in an editor.
   *
   * @param editor the editor
   * @param step the step
   * @param outcome receives the end
   */
  void replace(final EditorController editor, final MacroStep.Replace step, final Outcome outcome) {
    final TextMatcher matcher = compile(step.find(), step.options(), outcome);
    if (matcher == null) {
      return;
    }
    final ReplaceTemplate template;
    try {
      template = matcher.template(step.with());
    } catch (IllegalArgumentException e) {
      post(outcome, UiText.FIND_BAD_REPLACEMENT.format(e.getMessage()));
      return;
    }
    if (step.all()) {
      replaceAll(editor, matcher, template, outcome);
      return;
    }
    final Document document = editor.document();
    document.clearPreedit();
    final Selection primary = editor.selections().selections().primary();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final String ending = document.lineEnding().text();
    search(
        outcome,
        new Search() {
          @Override
          @WorkerThread
          public Runnable compute(CancellationToken token) {
            String with =
                matcher.replacementAt(snapshot, primary.start(), primary.end(), template, token);
            final String converted =
                with == null ? null : EditorController.convertBreaks(with, ending);
            return new Runnable() {
              @Override
              public void run() {
                if (document.revision() != revision) {
                  outcome.ended(UiText.FIND_REPLACE_STALE.text());
                  return;
                }
                int after = primary.end();
                if (converted != null) {
                  document.apply(
                      EditBatch.replace(
                          primary.start(), primary.end() - primary.start(), converted),
                      editor.selections(),
                      SelectionSet.caret(primary.start() + converted.length()));
                  after = primary.start() + converted.length();
                }
                select(editor, matcher, true, after, UiText.MACRO_NO_NEXT_MATCH, outcome);
              }
            };
          }
        });
  }

  private void replaceAll(
      EditorController editor,
      TextMatcher matcher,
      ReplaceTemplate template,
      final Outcome outcome) {
    final ReplaceAllRun run =
        new ReplaceAllRun(
            documents,
            workers,
            ui,
            new ReplaceAllRun.Outcome() {
              @Override
              public void ended(String notice, boolean error) {
                outcome.ended(error ? notice : null);
              }
            });
    cancel();
    replaceAll = run;
    run.start(editor, matcher, template);
  }

  /** Returns the step's query, or null after posting why it does not compile. */
  private TextMatcher compile(String text, MacroStep.Options options, Outcome outcome) {
    try {
      return new SearchQuery(text, options.regex(), options.matchCase(), options.wholeWord())
          .compile();
    } catch (PatternSyntaxException e) {
      post(outcome, FindController.badPattern(e));
      return null;
    }
  }

  /** Selects the first match from an offset; no match posts {@code missing}'s text, or a find's. */
  private void select(
      final EditorController editor,
      final TextMatcher matcher,
      final boolean forward,
      final int from,
      final UiText missing,
      final Outcome outcome) {
    final Document document = editor.document();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    search(
        outcome,
        new Search() {
          @Override
          @WorkerThread
          public Runnable compute(CancellationToken token) {
            int start = Math.min(from, snapshot.length());
            final Match match =
                forward
                    ? matcher.next(snapshot, start, token)
                    : matcher.previous(snapshot, start, token);
            return new Runnable() {
              @Override
              public void run() {
                if (document.revision() != revision) {
                  outcome.ended(UiText.FIND_REPLACE_STALE.text());
                } else if (match == null) {
                  outcome.ended((missing != null ? missing : UiText.MACRO_NO_MATCH).text());
                } else {
                  editor
                      .selections()
                      .set(SelectionSet.of(List.of(Selection.of(match.start(), match.end())), 0));
                  editor.showCaret();
                  outcome.ended(null);
                }
              }
            };
          }
        });
  }

  /** One search: computes on a worker, returns what the UI thread applies. */
  private interface Search {
    @WorkerThread
    Runnable compute(CancellationToken token);
  }

  private void search(final Outcome outcome, final Search search) {
    cancel();
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Runnable apply = null;
              String failure = UiText.FIND_FAILED.text();
              try {
                apply = search.compute(token);
                failure = null;
              } catch (CancellationException e) {
                return;
              } catch (PatternTooComplexException e) {
                failure = UiText.FIND_TOO_COMPLEX.text();
              } finally {
                deliver(token, outcome, apply, failure);
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  @WorkerThread
  private void deliver(
      final CancellationToken token,
      final Outcome outcome,
      final Runnable apply,
      final String failure) {
    if (token.isCanceled()) {
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (token != running) {
              return;
            }
            running = null;
            if (apply != null) {
              apply.run();
            } else {
              outcome.ended(failure);
            }
          }
        });
  }

  private void post(final Outcome outcome, final String failure) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            outcome.ended(failure);
          }
        });
  }
}
