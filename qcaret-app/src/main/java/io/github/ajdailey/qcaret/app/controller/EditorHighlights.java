package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.edit.Brackets;
import io.github.ajdailey.qcaret.core.edit.Occurrences;
import io.github.ajdailey.qcaret.core.edit.Pairs;
import io.github.ajdailey.qcaret.core.edit.Words;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps one editor view's bracket and occurrence highlights: the bracket beside the primary caret
 * with its partner, sought on the UI thread within the visible range and {@value
 * EditorController#BRACKET_SCAN} chars on each side and on a {@code qcaret-cpu-} worker beyond
 * that, and the whole-word occurrences of the word at the primary caret in the visible range, found
 * on a worker. Both are recomputed one UI turn after {@link #schedule}. An edit shifts both at
 * once. A result is dropped when a newer search replaced it or the document moved past its
 * revision; a bracket result also when the primary caret moved. Results go to the editor's current
 * view, so a search outlives a tab move, and a new view gets the ranges at once. Confined to the UI
 * thread.
 */
final class EditorHighlights implements DocumentListener {
  /** Occurrences of the word at the caret highlighted in one view at most. */
  static final int VIEW_OCCURRENCES = 2000;

  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private boolean pending;
  private boolean disposed;
  private CancellationToken bracketSearch;
  private CancellationToken occurrenceSearch;
  private String occurrenceKey;
  private int[] brackets = new int[0];
  private int[] occurrences = new int[0];

  EditorHighlights(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
    document.addListener(this);
  }

  /** Stops following the document and cancels both searches. */
  void dispose() {
    disposed = true;
    document.removeListener(this);
    if (bracketSearch != null) {
      bracketSearch.cancel();
    }
    if (occurrenceSearch != null) {
      occurrenceSearch.cancel();
    }
  }

  /** Shows the kept ranges on the editor's new view and recomputes both. */
  void viewAttached() {
    occurrenceKey = null;
    if (brackets.length > 0) {
      editor.view().showBrackets(brackets);
    }
    if (occurrences.length > 0) {
      editor.view().showOccurrences(occurrences);
    }
    schedule();
  }

  @Override
  public void textChanged(DocumentChange change) {
    if (editor.view() != null && (occurrences.length > 0 || brackets.length > 0)) {
      occurrences = FindHighlights.shifted(occurrences, change.edits());
      brackets = FindHighlights.shifted(brackets, change.edits());
      editor.view().showOccurrences(occurrences);
      editor.view().showBrackets(brackets);
    }
    schedule();
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // Highlights follow the text only.
  }

  /** Recomputes both highlights on the next UI turn, once. */
  void schedule() {
    if (pending || disposed) {
      return;
    }
    pending = true;
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            pending = false;
            if (!disposed && editor.view() != null) {
              highlightBrackets();
              highlightOccurrences();
            }
          }
        });
  }

  private void showBrackets(int[] ranges) {
    if (editor.view() != null && (ranges.length != 0 || brackets.length != 0)) {
      brackets = ranges;
      editor.view().showBrackets(ranges);
    }
  }

  private void showOccurrences(int[] ranges) {
    if (editor.view() != null && (ranges.length != 0 || occurrences.length != 0)) {
      occurrences = ranges;
      editor.view().showOccurrences(ranges);
    }
  }

  private void highlightBrackets() {
    if (bracketSearch != null) {
      bracketSearch.cancel();
      bracketSearch = null;
    }
    Selection primary = selections.selections().primary();
    final TextSnapshot text = document.text();
    final Pairs pairs = editor.bracketPairs();
    Brackets.Filter filter = editor.bracketFilter(text);
    final int at =
        primary.isEmpty() && selections.block() == null
            ? Brackets.at(text, primary.caret(), pairs, filter)
            : -1;
    if (at < 0) {
      showBrackets(new int[0]);
      return;
    }
    int scan = EditorController.BRACKET_SCAN;
    int from = Math.max(0, Math.min(editor.visibleStart(), at) - scan);
    int to = (int) Math.min(text.length(), (long) Math.max(editor.visibleEnd(), at + 1) + scan);
    int partner = Brackets.match(text, at, pairs, from, to, new CancellationToken(), filter);
    if (partner != Brackets.BEYOND) {
      showBrackets(bracketRanges(at, partner));
      return;
    }
    final long revision = document.revision();
    final int caret = primary.caret();
    final CancellationToken token = new CancellationToken();
    final Brackets.Filter workerFilter = editor.workerBracketFilter(text);
    bracketSearch = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int found;
              try {
                found = Brackets.match(text, at, pairs, 0, text.length(), token, workerFilter);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (token != bracketSearch) {
                        return;
                      }
                      bracketSearch = null;
                      if (!disposed
                          && document.revision() == revision
                          && selections.selections().primary().caret() == caret) {
                        showBrackets(bracketRanges(at, found));
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      bracketSearch = null;
    }
  }

  private static int[] bracketRanges(int at, int partner) {
    if (partner < 0) {
      return new int[0];
    }
    int a = Math.min(at, partner);
    int b = Math.max(at, partner);
    return new int[] {a, a + 1, b, b + 1};
  }

  private void highlightOccurrences() {
    Selection primary = selections.selections().primary();
    final TextSnapshot text = document.text();
    int[] word =
        primary.isEmpty() && selections.block() == null
            ? Words.at(text, primary.caret())
            : new int[0];
    if (word.length == 0) {
      occurrenceKey = null;
      if (occurrenceSearch != null) {
        occurrenceSearch.cancel();
        occurrenceSearch = null;
      }
      showOccurrences(new int[0]);
      return;
    }
    final String needle = text.subSequence(word[0], word[1]).toString();
    final long revision = document.revision();
    final int from = editor.visibleStart() - needle.length();
    final int to = editor.visibleEnd() + needle.length();
    String key = needle + '\n' + revision + '\n' + from + '\n' + to;
    if (key.equals(occurrenceKey)) {
      return;
    }
    occurrenceKey = key;
    if (occurrenceSearch != null) {
      occurrenceSearch.cancel();
    }
    final CancellationToken token = new CancellationToken();
    occurrenceSearch = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final int[] found;
              try {
                found = Occurrences.words(text, needle, from, to, VIEW_OCCURRENCES, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (token != occurrenceSearch) {
                        return;
                      }
                      occurrenceSearch = null;
                      if (!disposed && document.revision() == revision) {
                        showOccurrences(found.length >= 4 ? found : new int[0]);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      occurrenceSearch = null;
      occurrenceKey = null;
    }
  }
}
