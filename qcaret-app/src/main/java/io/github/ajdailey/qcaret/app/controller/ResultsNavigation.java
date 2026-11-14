package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * HyperSearch's navigation to a result: the set, group and match last opened, Next and Previous
 * Result, the stale check of a file group on a {@code qcaret-io-} worker, and the overview's match
 * offsets. Confined to the UI thread.
 */
final class ResultsNavigation {
  private final SearchController search;
  private final List<SearchController.Entry> entries;
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final CancellationToken checks = new CancellationToken();
  private long cursorSet = -1;
  private int cursorFile;
  private int cursorMatch = -1;

  ResultsNavigation(
      SearchController search,
      List<SearchController.Entry> entries,
      DocumentController documents,
      Workers workers,
      UiDispatcher ui,
      MainView view) {
    this.search = search;
    this.entries = entries;
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /** Stops the running stale checks. */
  void dispose() {
    checks.cancel();
  }

  /** A set was dropped: Next Result starts from the newest set with matches. */
  void forget(long id) {
    if (cursorSet == id) {
      cursorSet = -1;
    }
  }

  /** A set's results were replaced: Next Result starts from its first match. */
  void restart(long id) {
    if (cursorSet == id) {
      cursorMatch = -1;
    }
  }

  /** Returns the index of the set last navigated, or -1. */
  int lastSet() {
    return indexOf(cursorSet);
  }

  /** See {@link SearchController#next}. */
  void next(boolean forward) {
    int set = indexOf(cursorSet);
    if (set < 0 || entries.get(set).set.matches() == 0) {
      set = -1;
      for (int i = 0; i < entries.size() && set < 0; i++) {
        if (entries.get(i).set.matches() > 0) {
          set = i;
        }
      }
      if (set < 0) {
        view.showNotice(UiText.SEARCH_NO_RESULTS.text());
        return;
      }
      cursorSet = entries.get(set).set.id();
      cursorFile = 0;
      cursorMatch = -1;
    }
    List<FileResult> files = entries.get(set).set.files();
    int file = Math.min(cursorFile, files.size() - 1);
    int match = cursorMatch;
    if (match < 0) {
      file = forward ? 0 : files.size() - 1;
      while (files.get(file).matchCount() == 0) {
        file = (file + (forward ? 1 : files.size() - 1)) % files.size();
      }
      match = forward ? 0 : files.get(file).matchCount() - 1;
    } else {
      match += forward ? 1 : -1;
      while (match < 0 || match >= files.get(file).matchCount()) {
        file = (file + (forward ? 1 : files.size() - 1)) % files.size();
        match = forward ? 0 : files.get(file).matchCount() - 1;
      }
    }
    open(set, file, match);
  }

  private int indexOf(long id) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).set.id() == id) {
        return i;
      }
    }
    return -1;
  }

  /** See {@link SearchController#open}. */
  void open(int set, final int file, final int match) {
    final ResultSet r = entries.get(set).set;
    final FileResult f = r.files().get(file);
    cursorSet = r.id();
    cursorFile = file;
    cursorMatch = match;
    view.revealResult(set, file, match);
    Document document = f.document();
    if (document != null) {
      if (documents.holderOf(document) == null) {
        f.detach();
      } else {
        f.rebase();
        select(documents.showAnywhere(document), f, match);
        return;
      }
    }
    Path path = f.path();
    if (path == null) {
      view.showNotice(UiText.SEARCH_CLOSED.text());
      return;
    }
    Document opened = documents.documentAt(path);
    if (opened != null) {
      select(documents.show(opened), f, match);
      return;
    }
    if (f.stamp() != null && !f.stale()) {
      check(
          f,
          new Runnable() {
            @Override
            public void run() {
              openFile(f, match);
            }
          });
    } else {
      openFile(f, match);
    }
  }

  private void openFile(final FileResult f, final int match) {
    documents.open(
        f.path(),
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            select(editor, f, match);
          }
        });
  }

  /**
   * Selects a match in an editor when the text there is the text searched; otherwise marks it stale
   * and moves the caret to its line.
   */
  private void select(EditorController editor, FileResult f, int match) {
    if (editor == null || search.disposed()) {
      return;
    }
    editor.jumping();
    Document document = editor.document();
    TextSnapshot text = document.text();
    int start = f.start(match);
    int end = f.end(match);
    boolean same =
        !f.stale(match)
            && end <= text.length()
            && text.subSequence(start, end).toString().hashCode() == f.hit(match).hash();
    if (same) {
      if (f.document() == null) {
        f.attach(document);
      }
      editor.selections().set(SelectionSet.of(List.of(Selection.of(start, end)), 0));
    } else {
      f.markStale(match);
      int line = Math.min(f.line(match), text.lineCount() - 1);
      editor.selections().set(SelectionSet.caret(text.offsetOfLine(line)));
      view.showNotice(UiText.SEARCH_STALE.format(line + 1));
      view.resultsChanged(true);
    }
    if (editor.view() != null) {
      editor.view().showCaret();
    }
    documents.refreshOverview();
  }

  /**
   * Checks a file group's file on a {@code qcaret-io-} worker under the busy cursor: a changed
   * stamp whose bytes have another SHA-256 marks the group stale. Then runs {@code then} on the UI
   * thread, if given.
   */
  void check(final FileResult f, final Runnable then) {
    final Path path = f.path();
    final FileStamp stamp = f.stamp();
    final byte[] digest = f.digest();
    boolean started =
        new BusyWork(ui, view) {
          private boolean differs;

          @Override
          @WorkerThread
          void work() {
            differs = changed(path, stamp, digest, checks);
          }

          @Override
          void finished(boolean completed) {
            if (completed) {
              checked(f, differs, then);
            }
          }
        }.start(workers, Workers.Kind.IO);
    if (!started && then != null) {
      then.run();
    }
  }

  private void checked(FileResult f, boolean changed, Runnable then) {
    if (search.disposed()) {
      return;
    }
    if (changed && !f.stale()) {
      f.markStale();
      view.resultsChanged(true);
    }
    if (then != null) {
      then.run();
    }
  }

  /** Reports whether a file differs from the bytes searched. */
  @WorkerThread
  static boolean changed(Path path, FileStamp stamp, byte[] digest, CancellationToken token) {
    try {
      FileStamp now = FileStamp.read(path);
      if (stamp.equals(now)) {
        return false;
      }
      if (now == null || digest == null) {
        return true;
      }
      return !MessageDigest.isEqual(digest, Digests.bytesOf(path, token));
    } catch (IOException e) {
      return true;
    } catch (CancellationException e) {
      return false;
    }
  }

  /** A document closed: its groups keep their offsets and open the file from then on. */
  void detach(Document closed) {
    for (SearchController.Entry e : entries) {
      for (FileResult f : e.set.files()) {
        if (f.document() == closed) {
          f.detach();
        }
      }
    }
  }

  /** See {@link SearchController#offsets}. */
  int[] offsets(Document document) {
    int set = indexOf(cursorSet);
    if (set < 0) {
      set = entries.isEmpty() ? -1 : 0;
    }
    if (set < 0) {
      return new int[0];
    }
    int[] out = new int[0];
    for (FileResult f : entries.get(set).set.files()) {
      if (f.document() == document && !f.stale()) {
        f.rebase();
        int at = out.length;
        out = Arrays.copyOf(out, at + f.matchCount());
        for (int i = 0; i < f.matchCount(); i++) {
          out[at + i] = f.start(i);
        }
      }
    }
    return out;
  }
}
