package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Anchor;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Columns;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Go Back and Go Forward over caret positions across the window's documents, at most {@value
 * #LIMIT}, oldest dropped first. A position is recorded where the caret was before a jump: Go to
 * Line, Next or Previous Bookmark, a Bookmarks list row, a search result, Quick Open, and a change
 * of the selected document, which records the caret of the document left. A jump in the turn a
 * document was selected records nothing more, so opening a result in another document adds one
 * position. Ordinary caret moves record nothing, and neither does a position on the newest
 * position's line. Confined to the UI thread.
 *
 * <p>An open document's position is an {@link Anchor}, so it follows edits. When the document
 * closes, the position becomes its file, line and column; an untitled document's positions are
 * dropped. Going to a closed file's position checks on a {@code qcaret-io-} worker that the file
 * exists, then opens it; a missing file's position is dropped with a notice and the move goes on to
 * the next one. A jump after Go Back drops the positions ahead of the current one.
 */
final class NavigationHistory {
  /** Positions kept. */
  static final int LIMIT = 100;

  /** One position: an anchor in an open document, or a closed file's line and column. */
  private static final class Entry {
    Document document;
    Anchor anchor;
    Path path;
    int line;
    int column;
  }

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final List<Entry> entries = new ArrayList<Entry>();
  private final Runnable activeChanged =
      new Runnable() {
        @Override
        public void run() {
          activeChanged();
        }
      };
  private final Runnable endTurn =
      new Runnable() {
        @Override
        public void run() {
          switched = false;
        }
      };

  /** The entry the last move went to; {@code entries.size()} when at none. */
  private int at;

  private Document lastDocument;
  private boolean switched;
  private boolean navigating;
  private Path expected;
  private boolean disposed;
  private boolean notifiedBack;
  private boolean notifiedForward;

  NavigationHistory(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    documents.addActiveListener(activeChanged);
    documents.setJumpListener(
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            jumping(editor);
          }
        });
    lastDocument = documents.activeDocument();
  }

  /** Reports whether Go Back has a position. */
  boolean canBack() {
    return at > 0;
  }

  /** Reports whether Go Forward has a position. */
  boolean canForward() {
    return at < entries.size() - 1;
  }

  /** Returns the number of positions kept, for tests. */
  int size() {
    return entries.size();
  }

  /** Stops following the documents and drops every position. */
  void dispose() {
    disposed = true;
    documents.removeActiveListener(activeChanged);
    documents.setJumpListener(null);
    for (Entry e : entries) {
      release(e);
    }
    entries.clear();
    at = 0;
  }

  private void activeChanged() {
    if (disposed) {
      return;
    }
    closeEntries();
    Document now = documents.activeDocument();
    if (now != lastDocument) {
      Path path = documents.activePath();
      boolean arrived = expected != null && expected.equals(path);
      if (arrived) {
        expected = null;
      }
      if (!navigating && !arrived && lastDocument != null && documents.isOpen(lastDocument)) {
        EditorController left = documents.editorOf(lastDocument);
        if (left != null) {
          record(lastDocument, left.selections().selections().primary().caret());
        }
      }
      if (!switched) {
        switched = true;
        ui.asyncExec(endTurn);
      }
    }
    lastDocument = now;
  }

  private void jumping(EditorController editor) {
    if (disposed || navigating || switched) {
      return;
    }
    record(editor.document(), editor.selections().selections().primary().caret());
  }

  /** Drops the positions from the current one on, then adds a position unless it repeats. */
  private void record(Document document, int offset) {
    while (entries.size() > at) {
      release(entries.remove(entries.size() - 1));
    }
    if (!entries.isEmpty() && same(entries.get(entries.size() - 1), document, offset)) {
      changed();
      return;
    }
    Entry e = new Entry();
    e.document = document;
    e.anchor = document.anchor(offset, Bias.BEFORE);
    e.path = documents.pathOf(document);
    entries.add(e);
    if (entries.size() > LIMIT) {
      release(entries.remove(0));
    }
    at = entries.size();
    changed();
  }

  /** Moves to the previous position, recording the caret first when at none. */
  void back() {
    if (disposed) {
      return;
    }
    closeEntries();
    if (at == entries.size()) {
      EditorController editor = documents.activeEditor();
      Document document = documents.activeDocument();
      if (editor != null && document != null) {
        record(document, editor.selections().selections().primary().caret());
      }
      at = entries.size() - 1;
    }
    if (at <= 0) {
      at = Math.max(at, 0);
      changed();
      return;
    }
    at--;
    go(at, true);
  }

  /** Moves to the next position. */
  void forward() {
    if (disposed) {
      return;
    }
    closeEntries();
    if (at >= entries.size() - 1) {
      return;
    }
    at++;
    go(at, false);
  }

  private void go(int index, final boolean back) {
    final Entry e = entries.get(index);
    changed();
    if (e.document != null) {
      navigating = true;
      try {
        EditorController editor = documents.show(e.document);
        if (editor != null) {
          editor.moveCaret(e.anchor.offset());
          focus(editor);
        }
      } finally {
        navigating = false;
      }
      return;
    }
    final Path path = e.path;
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final boolean exists = Files.isRegularFile(path);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    reached(e, exists, back);
                  }
                });
          }
        });
  }

  /** Opens a closed file's position, or drops it and moves on when the file is gone. */
  private void reached(final Entry e, boolean exists, boolean back) {
    int index = entries.indexOf(e);
    if (disposed || index < 0) {
      return;
    }
    if (!exists) {
      view.showNotice(UiText.HISTORY_MISSING.format(e.path.getFileName()));
      entries.remove(index);
      if (back) {
        at = index;
        back();
      } else {
        at = index - 1;
        forward();
      }
      changed();
      return;
    }
    expected = e.path;
    documents.open(
        e.path,
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            expected = null;
            if (disposed || !entries.contains(e)) {
              return;
            }
            navigating = true;
            try {
              editor.goTo(e.line, e.column);
            } finally {
              navigating = false;
            }
            e.document = editor.document();
            e.anchor =
                e.document.anchor(editor.selections().selections().primary().caret(), Bias.BEFORE);
            focus(editor);
          }
        });
  }

  private static void focus(EditorController editor) {
    if (editor.view() != null) {
      editor.view().focus();
    }
  }

  /**
   * Turns the positions of closed documents into file positions, drops untitled ones, and keeps
   * each open document's path current.
   */
  private void closeEntries() {
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry e = entries.get(i);
      if (e.document == null) {
        continue;
      }
      if (documents.isOpen(e.document)) {
        e.path = documents.pathOf(e.document);
        continue;
      }
      if (e.path == null) {
        release(entries.remove(i));
        if (i < at) {
          at--;
        }
        continue;
      }
      TextSnapshot text = e.document.text();
      int offset = Math.min(e.anchor.offset(), text.length());
      e.line = text.lineAt(offset);
      LineView line = text.lineView(e.line, new LineView());
      e.column = Columns.column(line, offset - line.start(), e.document.tabWidth());
      release(e);
    }
    at = Math.min(at, entries.size());
  }

  private static void release(Entry e) {
    if (e.anchor != null) {
      e.anchor.dispose();
      e.anchor = null;
    }
    e.document = null;
  }

  /** Reports whether a position lies on an entry's line. */
  private boolean same(Entry e, Document document, int offset) {
    int line = document.text().lineAt(Math.min(offset, document.text().length()));
    if (e.document != null) {
      if (e.document != document) {
        return false;
      }
      return document.text().lineAt(Math.min(e.anchor.offset(), document.text().length())) == line;
    }
    return e.path != null && e.path.equals(documents.pathOf(document)) && e.line == line;
  }

  /** Syncs the menu when Go Back or Go Forward changes availability. */
  private void changed() {
    boolean back = canBack();
    boolean forward = canForward();
    if (back != notifiedBack || forward != notifiedForward) {
      notifiedBack = back;
      notifiedForward = forward;
      view.documentStateChanged();
    }
  }
}
