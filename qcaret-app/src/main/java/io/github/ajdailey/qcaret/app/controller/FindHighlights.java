package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.search.Match;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The find bar's match highlights: each view of the selected document shows the matches in its
 * visible range, at most {@value FindController#VIEW_MATCHES}, computed on a {@code qcaret-cpu-}
 * worker; a result for an older revision or a newer request is dropped. A text change shifts the
 * shown highlights at once and recomputes them. Confined to the UI thread.
 */
final class FindHighlights {
  private final FindController find;
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Map<EditorController, CancellationToken> highlighting =
      new IdentityHashMap<EditorController, CancellationToken>();
  private final Map<EditorController, int[]> shown = new IdentityHashMap<EditorController, int[]>();
  private final DocumentListener changes =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          shift(change);
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Highlights follow the text only.
        }
      };
  private Document followed;

  FindHighlights(
      FindController find, DocumentController documents, Workers workers, UiDispatcher ui) {
    this.find = find;
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
  }

  /** Stops the running highlights and stops following the document. */
  void dispose() {
    for (CancellationToken token : highlighting.values()) {
      token.cancel();
    }
    highlighting.clear();
    follow(null);
  }

  /** Removes every shown highlight. */
  void clear() {
    clearHighlights(new ArrayList<EditorController>(shown.keySet()));
  }

  /** Recomputes the highlights of every view of the selected document; clears the others. */
  void refresh() {
    List<EditorController> views =
        find.isOpen() && find.matcher() != null && !find.disposed()
            ? documents.activeEditors()
            : new ArrayList<EditorController>();
    List<EditorController> stale = new ArrayList<EditorController>();
    for (EditorController e : shown.keySet()) {
      if (!views.contains(e)) {
        stale.add(e);
      }
    }
    for (EditorController e : highlighting.keySet()) {
      if (!views.contains(e) && !stale.contains(e)) {
        stale.add(e);
      }
    }
    clearHighlights(stale);
    follow(views.isEmpty() ? null : views.get(0).document());
    for (EditorController e : views) {
      highlight(e);
    }
  }

  private void clearHighlights(List<EditorController> editors) {
    for (EditorController e : editors) {
      CancellationToken token = highlighting.remove(e);
      if (token != null) {
        token.cancel();
      }
      if (shown.remove(e) != null && e.view() != null) {
        e.view().showMatches(new int[0]);
      }
    }
  }

  private void follow(Document document) {
    if (document == followed) {
      return;
    }
    if (followed != null) {
      followed.removeListener(changes);
    }
    followed = document;
    if (document != null) {
      document.addListener(changes);
    }
  }

  /** Computes one view's visible matches on a worker. */
  void highlight(final EditorController editor) {
    CancellationToken old = highlighting.get(editor);
    if (old != null) {
      old.cancel();
    }
    final int from = editor.visibleStart();
    final int to = editor.visibleEnd();
    final Document document = editor.document();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final TextMatcher m = find.matcher();
    final CancellationToken token = new CancellationToken();
    highlighting.put(editor, token);
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              int[] ranges;
              try {
                ranges = visibleMatches(m, snapshot, from, to, token);
              } catch (CancellationException e) {
                return;
              } catch (PatternTooComplexException e) {
                ranges = new int[0];
              }
              final int[] found = ranges;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (highlighting.get(editor) != token || document.revision() != revision) {
                        return;
                      }
                      highlighting.remove(editor);
                      if (editor.view() != null) {
                        shown.put(editor, found);
                        editor.view().showMatches(found);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      highlighting.remove(editor);
    }
  }

  /** The matches starting in {@code [from, to)} and on the part of its first line before it. */
  @WorkerThread
  static int[] visibleMatches(
      TextMatcher m, TextSnapshot text, int from, int to, CancellationToken token) {
    int end = Math.min(to, text.length());
    if (end <= from) {
      return new int[0];
    }
    int lineStart = text.offsetOfLine(text.lineAt(from));
    int start = Math.max(lineStart, from - DisplayProjection.MAX_ROW_CHARS);
    List<Match> matches = m.all(text, start, end, FindController.VIEW_MATCHES, token);
    int[] ranges = new int[matches.size() * 2];
    for (int i = 0; i < matches.size(); i++) {
      ranges[2 * i] = matches.get(i).start();
      ranges[2 * i + 1] = matches.get(i).end();
    }
    return ranges;
  }

  /** Shifts the shown highlights through a change, drops those it touches, and recomputes. */
  private void shift(DocumentChange change) {
    List<EditorController> editors = new ArrayList<EditorController>(shown.keySet());
    for (EditorController e : editors) {
      int[] mappedRanges = shifted(shown.get(e), change.edits());
      shown.put(e, mappedRanges);
      if (e.view() != null) {
        e.view().showMatches(mappedRanges);
      }
    }
    if (find.isOpen() && find.matcher() != null) {
      for (EditorController e : documents.activeEditors()) {
        highlight(e);
      }
    }
  }

  /** Maps ranges through edits given by ascending offset; a range an edit touches is dropped. */
  static int[] shifted(int[] ranges, List<TextEdit> edits) {
    int[] out = new int[ranges.length];
    int n = 0;
    int e = 0;
    int shift = 0;
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      int start = ranges[i];
      int end = ranges[i + 1];
      while (e < edits.size() && edits.get(e).offset() + edits.get(e).removed().length() <= start) {
        TextEdit edit = edits.get(e);
        shift += edit.inserted().length() - edit.removed().length();
        e++;
      }
      boolean touched = e < edits.size() && edits.get(e).offset() < end;
      if (!touched) {
        out[n++] = start + shift;
        out[n++] = end + shift;
      }
    }
    return Arrays.copyOf(out, n);
  }
}
