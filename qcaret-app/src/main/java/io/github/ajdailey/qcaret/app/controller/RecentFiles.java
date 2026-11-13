package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * File > Recent Files: the last {@value #LIMIT} files opened in any window, newest first, each
 * once; the session saves them. Documents hold real paths, so a file opened through a link and
 * through its target is one entry. Confined to the UI thread; Clean Up checks the files on a {@code
 * qcaret-io-} worker.
 */
final class RecentFiles {
  /** Files kept. */
  static final int LIMIT = 20;

  private final DocumentController documents;
  private final MainView view;
  private final List<Path> paths;
  private final Workers workers;
  private final UiDispatcher ui;
  private Predicate<Path> probe;
  private boolean cleaning;
  private boolean disposed;

  RecentFiles(
      DocumentController documents,
      MainView view,
      List<Path> shared,
      Workers workers,
      UiDispatcher ui) {
    this.documents = documents;
    this.view = view;
    this.paths = shared;
    this.workers = workers;
    this.ui = ui;
    documents.setOpenListener(
        new Consumer<Path>() {
          @Override
          public void accept(Path path) {
            add(path);
          }
        });
  }

  /** Moves a file to the top, dropping the oldest past {@link #LIMIT}. */
  void add(Path path) {
    paths.remove(path);
    paths.add(0, path);
    if (paths.size() > LIMIT) {
      paths.remove(paths.size() - 1);
    }
    view.documentStateChanged();
  }

  /** Replaces the list with a session's, newest first. */
  void restore(List<Path> files) {
    paths.clear();
    paths.addAll(files.subList(0, Math.min(LIMIT, files.size())));
    view.documentStateChanged();
  }

  /** Moves the entries under a renamed entry, keeping their order. */
  void moved(PathMove move) {
    for (int i = 0; i < paths.size(); i++) {
      paths.set(i, move.real(paths.get(i)));
    }
    view.documentStateChanged();
  }

  /** Returns the files, newest first. */
  List<Path> paths() {
    return List.copyOf(paths);
  }

  /** Opens a listed file, or selects its tab. */
  void open(int index) {
    if (index >= 0 && index < paths.size()) {
      documents.open(List.of(paths.get(index)));
    }
  }

  /** Opens every listed file; each one opened moves to the top as any open does. */
  void openAll() {
    documents.openAll(paths, null);
  }

  /** Empties the list. */
  void clear() {
    paths.clear();
    view.documentStateChanged();
  }

  /**
   * Drops the files that no longer exist. The check runs on a worker, so a slow or unreachable path
   * holds only that worker; a Clean Up in this window while one runs does nothing. A notice reports
   * the count.
   */
  void cleanUp() {
    if (cleaning || disposed) {
      return;
    }
    final List<Path> listed = List.copyOf(paths);
    final Predicate<Path> check = probe;
    cleaning =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final List<Path> gone = new ArrayList<Path>();
                try {
                  for (Path path : listed) {
                    if (check == null ? isGone(path) : check.test(path)) {
                      gone.add(path);
                    }
                  }
                } finally {
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          cleaned(gone);
                        }
                      });
                }
              }
            });
  }

  private void cleaned(List<Path> gone) {
    cleaning = false;
    if (disposed) {
      return;
    }
    paths.removeAll(gone);
    view.documentStateChanged();
    view.showNotice(
        gone.isEmpty()
            ? UiText.RECENT_CLEAN_NONE.text()
            : UiText.RECENT_CLEANED.format(
                UiText.count(gone.size(), UiText.COUNT_FILE, UiText.COUNT_FILES)));
  }

  /**
   * Reports whether a file is gone: nothing at the path, which includes a path under an unmounted
   * mount point, a dangling link, or a folder. A path whose check fails otherwise, such as one
   * denied or unreachable, stays listed.
   */
  @WorkerThread
  static boolean isGone(Path path) {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class).isDirectory();
    } catch (NoSuchFileException e) {
      return true;
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  /** Replaces the check Clean Up runs on each file, for tests. */
  void setProbe(Predicate<Path> probe) {
    this.probe = probe;
  }

  /** Drops a Clean Up result that arrives after the window closed. */
  void dispose() {
    disposed = true;
  }
}
