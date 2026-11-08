package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BookmarkRow;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The Bookmarks list: the selected document's first {@value #MAX_ROWS} bookmarks by line, each with
 * its comment and the first {@value #TEXT_CHARS} chars of its line. It follows the selected tab and
 * its closing, and reads again after an edit or a bookmark change. Nothing is read while the list
 * is hidden. Confined to the UI thread.
 *
 * <p>A read waits {@value #DELAY_MILLIS} ms, so an edit burst reads once, then builds the rows on a
 * {@code qcaret-cpu-} worker from a text snapshot and the bookmarks' offsets; the rows show only
 * while the document is still at that revision, and a newer read is requested otherwise. Each row
 * reserves {@value #ROW_BYTES} bytes plus 2 per char from the {@link MemoryBudget} while listed;
 * rows past a refusal are left out, as past {@value #MAX_ROWS}, and the summary says so. Go to,
 * edit and remove act on the row's bookmark wherever edits have moved it since the read.
 */
public final class BookmarkList implements BookmarksInput {
  /** Most rows listed. */
  public static final int MAX_ROWS = 10_000;

  /** Chars of each line's text listed. */
  public static final int TEXT_CHARS = 160;

  /** Bytes charged per row besides its chars. */
  static final long ROW_BYTES = 96;

  /** Wait after a change before reading. */
  static final int DELAY_MILLIS = 150;

  /** What a worker built: the rows, their bookmarks' keys, the total and the bytes reserved. */
  record Snapshot(
      long revision, List<BookmarkRow> rows, List<Object> keys, int total, long charged) {}

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          read();
        }
      };
  private final Runnable bookmarksChanged =
      new Runnable() {
        @Override
        public void run() {
          request();
        }
      };
  private final Runnable activeChanged =
      new Runnable() {
        @Override
        public void run() {
          bind(documents.activeDocument());
        }
      };
  private final DocumentListener edits =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          request();
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Rows follow the text and the bookmarks only.
        }
      };
  private boolean shown;
  private boolean disposed;
  private Document document;
  private Bookmarks bookmarks;
  private List<BookmarkRow> rows = List.of();
  private List<Object> keys = List.of();
  private int total;
  private long charged;
  private CancellationToken running;

  BookmarkList(
      DocumentController documents,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
  }

  /**
   * Reports whether the list shows.
   *
   * @return true while shown
   */
  boolean shown() {
    return shown;
  }

  /** Shows the list, or hides it. */
  void toggle() {
    setShown(!shown);
  }

  private void setShown(boolean value) {
    if (disposed || value == shown) {
      return;
    }
    shown = value;
    if (shown) {
      documents.addActiveListener(activeChanged);
      bind(documents.activeDocument());
    } else {
      documents.removeActiveListener(activeChanged);
      unbind();
    }
    view.showBookmarks(this, shown);
    view.documentStateChanged();
    documents.sessionChanged();
  }

  /** Shows the list when a session saved it shown. */
  void restore(boolean show) {
    setShown(show);
  }

  /** Stops following the documents and returns the rows' reservation. */
  void dispose() {
    if (shown) {
      documents.removeActiveListener(activeChanged);
    }
    disposed = true;
    shown = false;
    unbind();
  }

  /** Returns the listed document, or null. */
  Document document() {
    return document;
  }

  /** Returns the bytes the listed rows reserve. */
  long charged() {
    return charged;
  }

  private void bind(Document next) {
    Bookmarks marks = next == null ? null : documents.bookmarksOf(next);
    if (next == document && marks == bookmarks) {
      return;
    }
    unbind();
    if (marks != null) {
      document = next;
      bookmarks = marks;
      document.addListener(edits);
      bookmarks.addListener(bookmarksChanged);
      ui.cancelTimer(timer);
      read();
    }
    view.bookmarksChanged();
  }

  private void unbind() {
    ui.cancelTimer(timer);
    cancel();
    if (document != null) {
      document.removeListener(edits);
      bookmarks.removeListener(bookmarksChanged);
    }
    document = null;
    bookmarks = null;
    show(List.<BookmarkRow>of(), List.of(), 0, 0);
  }

  private void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  /** Reads after {@link #DELAY_MILLIS}, replacing a pending request. */
  private void request() {
    if (disposed || document == null) {
      return;
    }
    ui.cancelTimer(timer);
    ui.timerExec(DELAY_MILLIS, timer);
  }

  private void read() {
    if (disposed || document == null) {
      return;
    }
    cancel();
    final List<Bookmarks.Mark> marks = bookmarks.marks();
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    final CancellationToken token = new CancellationToken();
    running = token;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Snapshot built = null;
              try {
                built = rows(text, revision, marks, budget, token);
              } catch (CancellationException e) {
                built = null;
              } finally {
                final Snapshot result = built;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        built(token, result);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  /** Shows a worker's rows when they are current; returns their reservation otherwise. */
  private void built(CancellationToken token, Snapshot snapshot) {
    if (token != running || disposed || document == null) {
      if (snapshot != null) {
        budget.release(snapshot.charged());
      }
      return;
    }
    running = null;
    if (snapshot == null) {
      return;
    }
    if (document.revision() != snapshot.revision()) {
      budget.release(snapshot.charged());
      request();
      return;
    }
    show(snapshot.rows(), snapshot.keys(), snapshot.total(), snapshot.charged());
    view.bookmarksChanged();
  }

  private void show(List<BookmarkRow> newRows, List<Object> newKeys, int newTotal, long bytes) {
    budget.release(charged);
    rows = newRows;
    keys = newKeys;
    total = newTotal;
    charged = bytes;
  }

  /**
   * Builds the rows of bookmarks by offset.
   *
   * @param text the text the offsets point into
   * @param revision its revision
   * @param marks the bookmarks, ascending
   * @param budget charged for each row
   * @param token checked between rows
   * @return the rows
   */
  @WorkerThread
  static Snapshot rows(
      TextSnapshot text,
      long revision,
      List<Bookmarks.Mark> marks,
      MemoryBudget budget,
      CancellationToken token) {
    int count = Math.min(marks.size(), MAX_ROWS);
    List<BookmarkRow> out = new ArrayList<BookmarkRow>(count);
    List<Object> outKeys = new ArrayList<Object>(count);
    long reserved = 0;
    LineView line = new LineView();
    try {
      for (int i = 0; i < count; i++) {
        token.check();
        Bookmarks.Mark mark = marks.get(i);
        int number = text.lineAt(Math.min(mark.offset(), text.length()));
        text.lineView(number, line);
        String comment = mark.comment() == null ? "" : mark.comment();
        String shown = shown(line);
        long cost = ROW_BYTES + 2L * (comment.length() + shown.length());
        if (!budget.reserve(cost)) {
          break;
        }
        reserved += cost;
        out.add(new BookmarkRow(number, comment, shown));
        outKeys.add(mark.key());
      }
      long done = reserved;
      reserved = 0;
      return new Snapshot(revision, List.copyOf(out), List.copyOf(outKeys), marks.size(), done);
    } finally {
      budget.release(reserved);
    }
  }

  /** Cuts a line to {@link #TEXT_CHARS} chars with tabs and control chars as spaces. */
  private static String shown(LineView line) {
    int length = Math.min(line.length(), TEXT_CHARS);
    char[] out = new char[length];
    for (int i = 0; i < length; i++) {
      char c = line.charAt(i);
      out[i] = c < 0x20 || (c >= 0x7f && c < 0xa0) ? ' ' : c;
    }
    return new String(out);
  }

  // BookmarksInput.

  @Override
  public int rowCount() {
    return rows.size();
  }

  @Override
  public BookmarkRow row(int index) {
    return rows.get(index);
  }

  @Override
  public String summary() {
    if (document == null) {
      return UiText.BOOKMARKS_NO_DOCUMENT.text();
    }
    String name = documents.nameOf(document);
    return rows.size() < total
        ? UiText.BOOKMARKS_SUMMARY_CUT.format(name, rows.size(), total)
        : UiText.BOOKMARKS_SUMMARY.format(
            name, UiText.count(total, UiText.COUNT_BOOKMARK, UiText.COUNT_BOOKMARKS));
  }

  /** Returns the line a row's bookmark marks; -1 when the bookmark is gone. */
  private int line(int index) {
    if (bookmarks == null || index < 0 || index >= keys.size()) {
      return -1;
    }
    return bookmarks.line(keys.get(index));
  }

  @Override
  public void goTo(int index) {
    int line = line(index);
    if (line >= 0) {
      EditorController editor = documents.show(document);
      if (editor != null) {
        editor.goTo(line, 0);
        if (editor.view() != null) {
          editor.view().focus();
        }
      }
    }
  }

  @Override
  public void editComment(int index) {
    int line = line(index);
    if (line >= 0) {
      documents.editBookmarkComment(document, line);
    }
  }

  @Override
  public void remove(int index) {
    int line = line(index);
    if (line >= 0) {
      bookmarks.remove(line);
    }
  }

  @Override
  public void hide() {
    setShown(false);
  }
}
