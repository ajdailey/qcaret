package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MainView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * File > Recent Files for the run: the last {@value #LIMIT} files opened, newest first, each once.
 * Documents hold real paths, so a file opened through a link and through its target is one entry.
 * Confined to the UI thread.
 */
final class RecentFiles {
  /** Files kept. */
  static final int LIMIT = 20;

  private final DocumentController documents;
  private final MainView view;
  private final List<Path> paths = new ArrayList<Path>();

  RecentFiles(DocumentController documents, MainView view) {
    this.documents = documents;
    this.view = view;
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

  /** Empties the list. */
  void clear() {
    paths.clear();
    view.documentStateChanged();
  }
}
