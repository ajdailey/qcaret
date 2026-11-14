package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Brackets;
import io.github.ajdailey.qcaret.core.edit.Occurrences;
import io.github.ajdailey.qcaret.core.edit.Pairs;
import io.github.ajdailey.qcaret.core.edit.SelectionExpansion;
import io.github.ajdailey.qcaret.core.edit.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * One view's Select All Occurrences, Skip Occurrence, Expand Selection, Select Line, Undo Selection
 * and Redo Selection ({@link SelectionHistory}).
 *
 * <ul>
 *   <li>Select All Occurrences selects every occurrence of the primary selection's text, or every
 *       whole-word occurrence of the word at a primary caret. Past {@value #MAX_OCCURRENCES} it
 *       selects nothing and shows a notice. Occurrences that touch stay separate selections ({@link
 *       SelectionSet#apart}), here and in Skip Occurrence.
 *   <li>Skip Occurrence drops the primary selection, which Add Next Occurrence added last, and
 *       selects the next occurrence of its text as the new primary; at a caret it selects the word.
 *   <li>Expand Selection follows {@link SelectionExpansion#expand}, with brackets in strings and
 *       comments left out as bracket matching leaves them; Select Line follows {@link
 *       SelectionExpansion#lines}.
 * </ul>
 *
 * <p>A search over a text longer than {@value #SYNC_CHARS} chars, and a bracket search that reaches
 * {@value EditorController#BRACKET_SCAN} chars, runs on a {@code qcaret-cpu-} worker under the
 * editor's busy cursor; its result applies only at the same revision and selections, and a result
 * for an older revision is dropped with a notice. Esc cancels it. Confined to the UI thread.
 */
final class SelectionCommands {
  /** Occurrences Select All Occurrences selects at most. */
  static final int MAX_OCCURRENCES = 10_000;

  /** Text length up to which a search runs on the UI thread. */
  static final int SYNC_CHARS = 1 << 16;

  /** A search on a text snapshot, run on the UI thread for a short text, else on a worker. */
  private interface Search<T> {
    T run(CancellationToken token);
  }

  /** What applies a search's result on the UI thread. */
  private interface Apply<T> {
    void apply(T result);
  }

  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private final SelectionHistory history;
  private CancellationToken work;
  private boolean disposed;

  SelectionCommands(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
    history = new SelectionHistory(selections);
  }

  /** Returns the view's selection history. */
  SelectionHistory history() {
    return history;
  }

  /**
   * Runs a selection command.
   *
   * @param command one of the commands this class lists
   */
  void execute(CommandId command) {
    switch (command) {
      case SELECT_ALL_OCCURRENCES -> selectAllOccurrences();
      case SKIP_OCCURRENCE -> skipOccurrence();
      case EXPAND_SELECTION -> expand();
      case SELECT_LINE -> show(SelectionExpansion.lines(document.text(), selections.selections()));
      case UNDO_SELECTION -> {
        if (history.undo()) {
          editor.showCaret();
        }
      }
      case REDO_SELECTION -> {
        if (history.redo()) {
          editor.showCaret();
        }
      }
      default -> throw new IllegalArgumentException(command.name());
    }
  }

  /** Cancels a running search. */
  void cancel() {
    if (work != null) {
      work.cancel();
    }
  }

  /** Cancels a running search and stops following the view. */
  void dispose() {
    disposed = true;
    cancel();
    history.dispose();
  }

  private void selectAllOccurrences() {
    final TextSnapshot text = document.text();
    final Selection primary = selections.selections().primary();
    final int[] range =
        primary.isEmpty()
            ? Words.at(text, primary.caret())
            : new int[] {primary.start(), primary.end()};
    if (range.length == 0) {
      return;
    }
    final String needle = text.subSequence(range[0], range[1]).toString();
    final boolean whole = primary.isEmpty();
    run(
        text,
        new Search<int[]>() {
          @Override
          public int[] run(CancellationToken token) {
            return whole
                ? Occurrences.words(text, needle, 0, text.length(), MAX_OCCURRENCES + 1, token)
                : Occurrences.all(text, needle, MAX_OCCURRENCES + 1, token);
          }
        },
        new Apply<int[]>() {
          @Override
          public void apply(int[] found) {
            occurrencesFound(found, range[0]);
          }
        });
  }

  private void occurrencesFound(int[] found, int primaryStart) {
    int count = found.length / 2;
    if (count > MAX_OCCURRENCES) {
      editor.notice(UiText.OCCURRENCES_LIMIT.format(MAX_OCCURRENCES));
      return;
    }
    if (count == 0) {
      return;
    }
    List<Selection> list = new ArrayList<Selection>(count);
    int primary = 0;
    for (int i = 0; i < count; i++) {
      list.add(Selection.of(found[2 * i], found[2 * i + 1]));
      if (found[2 * i] <= primaryStart) {
        primary = i;
      }
    }
    show(SelectionSet.apart(list, primary));
  }

  private void skipOccurrence() {
    final SelectionSet set = selections.selections();
    final Selection primary = set.primary();
    if (primary.isEmpty()) {
      editor.execute(CommandId.ADD_NEXT_OCCURRENCE);
      return;
    }
    final TextSnapshot text = document.text();
    final String needle = text.subSequence(primary.start(), primary.end()).toString();
    run(
        text,
        new Search<Integer>() {
          @Override
          public Integer run(CancellationToken token) {
            return Occurrences.next(text, needle, primary.end(), token);
          }
        },
        new Apply<Integer>() {
          @Override
          public void apply(Integer found) {
            skipped(set, found, needle.length());
          }
        });
  }

  private void skipped(SelectionSet set, int found, int length) {
    if (found < 0 || found == set.primary().start()) {
      return;
    }
    List<Selection> list = new ArrayList<Selection>(set.selections());
    list.remove(set.primaryIndex());
    Selection next = Selection.of(found, found + length);
    for (int i = 0; i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() < next.end() && next.start() < s.end()) {
        show(SelectionSet.apart(list, i));
        return;
      }
    }
    list.add(next);
    show(SelectionSet.apart(list, list.size() - 1));
  }

  private void expand() {
    final TextSnapshot text = document.text();
    final SelectionSet set = selections.selections();
    final Pairs pairs = editor.bracketPairs();
    SelectionSet expanded =
        SelectionExpansion.expand(
            text,
            set,
            pairs,
            editor.bracketFilter(text),
            EditorController.BRACKET_SCAN,
            new CancellationToken());
    if (expanded != null) {
      show(expanded);
      return;
    }
    final Brackets.Filter filter = editor.workerBracketFilter(text);
    startWorker(
        new Search<SelectionSet>() {
          @Override
          public SelectionSet run(CancellationToken token) {
            return SelectionExpansion.expand(text, set, pairs, filter, Integer.MAX_VALUE, token);
          }
        },
        new Apply<SelectionSet>() {
          @Override
          public void apply(SelectionSet result) {
            show(result);
          }
        });
  }

  private void show(SelectionSet set) {
    if (!set.equals(selections.selections()) || selections.block() != null) {
      selections.set(set);
      editor.showCaret();
    }
  }

  /** Runs a search on the UI thread for a short text, else on a worker. */
  private <T> void run(TextSnapshot text, Search<T> search, Apply<T> apply) {
    if (text.length() <= SYNC_CHARS) {
      apply.apply(search.run(new CancellationToken()));
    } else {
      startWorker(search, apply);
    }
  }

  private <T> void startWorker(final Search<T> search, final Apply<T> apply) {
    final long revision = document.revision();
    final SelectionSet set = selections.selections();
    cancel();
    final CancellationToken token = new CancellationToken();
    work = token;
    boolean started =
        new BusyWork(ui, editor.busyCursor()) {
          private T found;

          @Override
          @WorkerThread
          void work() {
            try {
              found = search.run(token);
            } catch (CancellationException e) {
              // Esc, a newer search, or the view closed.
            }
          }

          @Override
          void finished(boolean completed) {
            SelectionCommands.this.finished(token, revision, set, found, apply);
          }
        }.startCpu(workers);
    if (!started) {
      work = null;
    }
  }

  private <T> void finished(
      CancellationToken token, long revision, SelectionSet set, T found, Apply<T> apply) {
    if (token != work) {
      return;
    }
    work = null;
    if (disposed || found == null || token.isCanceled()) {
      return;
    }
    if (document.revision() != revision) {
      editor.notice(UiText.OCCURRENCES_STALE.text());
    } else if (set.equals(selections.selections())) {
      apply.apply(found);
    }
  }
}
