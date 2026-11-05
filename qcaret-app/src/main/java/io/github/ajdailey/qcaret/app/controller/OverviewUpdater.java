package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.search.Match;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Computes one editor view's overview strip marks: the find bar's matches, the HyperSearch matches
 * and the bookmarks over the whole document. A request waits {@value #DELAY_MILLIS} ms, so a burst
 * of edits computes once; the marks are computed on a {@code qcaret-cpu-} worker from a snapshot
 * and shown only while the document is still at that revision and the strip has the same height.
 * Nothing is computed while the strip is hidden or has no height. Confined to the UI thread.
 */
final class OverviewUpdater implements DocumentListener {
  /** Wait after a request before computing. */
  static final int DELAY_MILLIS = 150;

  /** Find matches marked at most. */
  static final int MAX_FIND_MATCHES = 1_000_000;

  private final EditorController editor;
  private final Document document;
  private final Bookmarks bookmarks;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Supplier<OverviewSources> sources;
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          compute();
        }
      };
  private final Runnable bookmarksChanged =
      new Runnable() {
        @Override
        public void run() {
          request();
        }
      };
  private int height;
  private boolean shown = true;
  private CancellationToken running;
  private boolean disposed;
  private OverviewMarks marks = OverviewMarks.EMPTY;

  OverviewUpdater(
      EditorController editor,
      Bookmarks bookmarks,
      Workers workers,
      UiDispatcher ui,
      Supplier<OverviewSources> sources) {
    this.editor = editor;
    this.document = editor.document();
    this.bookmarks = bookmarks;
    this.workers = workers;
    this.ui = ui;
    this.sources = sources;
    document.addListener(this);
    bookmarks.addListener(bookmarksChanged);
  }

  /** Returns the marks shown last; {@link OverviewMarks#EMPTY} before the first. */
  OverviewMarks marks() {
    return marks;
  }

  /** Sets the strip's height in pixels and computes again when it changed. */
  void setHeight(int pixels) {
    if (pixels != height) {
      height = pixels;
      request();
    }
  }

  /** Shows or hides the strip; a shown strip computes again. */
  void setShown(boolean value) {
    shown = value;
    if (shown) {
      request();
    } else {
      cancel();
    }
  }

  /** Computes the marks after {@link #DELAY_MILLIS}, replacing a pending request. */
  void request() {
    if (disposed || !shown || height <= 0) {
      return;
    }
    ui.cancelTimer(timer);
    ui.timerExec(DELAY_MILLIS, timer);
  }

  /** Stops following the document and drops a running computation. */
  void dispose() {
    disposed = true;
    ui.cancelTimer(timer);
    cancel();
    document.removeListener(this);
    bookmarks.removeListener(bookmarksChanged);
  }

  private void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  @Override
  public void textChanged(DocumentChange change) {
    request();
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // The marks follow the text only.
  }

  private void compute() {
    if (disposed || !shown || height <= 0 || editor.view() == null) {
      return;
    }
    cancel();
    OverviewSources from = sources.get();
    final TextMatcher find = from == null ? null : from.findMatcher(document);
    final int[] search = from == null ? new int[0] : from.searchOffsets(document);
    final int[] marked = bookmarks.lines();
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    final int rows = height;
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final OverviewMarks computed;
              try {
                computed = marks(text, revision, rows, find, search, marked, token);
              } catch (CancellationException e) {
                return;
              }
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      show(token, computed);
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  private void show(CancellationToken token, OverviewMarks computed) {
    if (token != running || disposed) {
      return;
    }
    running = null;
    if (document.revision() != computed.revision() || computed.height() != height) {
      request();
      return;
    }
    marks = computed;
    if (editor.view() != null) {
      editor.view().showOverview(computed);
    }
  }

  /**
   * Computes the marks of a text.
   *
   * @param text the text
   * @param revision its revision
   * @param height the strip's pixel rows
   * @param find the find query, or null
   * @param search HyperSearch match starts in {@code text}
   * @param bookmarks bookmarked lines, ascending
   * @param token checked while matching
   * @return the marks
   */
  @WorkerThread
  static OverviewMarks marks(
      TextSnapshot text,
      long revision,
      int height,
      TextMatcher find,
      int[] search,
      int[] bookmarks,
      CancellationToken token) {
    int[] findLines = new int[0];
    if (find != null) {
      try {
        List<Match> found = find.all(text, 0, text.length(), MAX_FIND_MATCHES, token);
        findLines = new int[found.size()];
        for (int i = 0; i < findLines.length; i++) {
          findLines[i] = text.lineAt(found.get(i).start());
        }
      } catch (PatternTooComplexException e) {
        findLines = new int[0];
      }
    }
    int[] searchLines = new int[search.length];
    for (int i = 0; i < search.length; i++) {
      searchLines[i] = text.lineAt(Math.max(0, Math.min(search[i], text.length())));
    }
    Arrays.sort(searchLines);
    token.check();
    return OverviewMarks.of(text.lineCount(), revision, height, findLines, searchLines, bookmarks);
  }
}
