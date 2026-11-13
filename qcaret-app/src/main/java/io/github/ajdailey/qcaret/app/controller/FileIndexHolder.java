package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The workspace folder's file index: built on a {@code qcaret-search-} worker, a new build
 * canceling the running one, and charged to the {@link MemoryBudget}. A dropped, replaced or stale
 * index returns its charge. Confined to the UI thread.
 */
final class FileIndexHolder {
  private final WorkspaceController workspace;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final List<Runnable> indexListeners = new ArrayList<Runnable>();
  private FileIndex index;
  private CancellationToken indexing;

  FileIndexHolder(
      WorkspaceController workspace, Workers workers, UiDispatcher ui, MemoryBudget budget) {
    this.workspace = workspace;
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
  }

  /** See {@link WorkspaceController#index}. */
  FileIndex index() {
    return index;
  }

  /** See {@link WorkspaceController#indexing}. */
  boolean indexing() {
    return indexing != null;
  }

  /** See {@link WorkspaceController#addIndexListener}. */
  void addIndexListener(Runnable listener) {
    indexListeners.add(listener);
  }

  /** See {@link WorkspaceController#removeIndexListener}. */
  void removeIndexListener(Runnable listener) {
    indexListeners.remove(listener);
  }

  /** Drops the index and returns its charge. */
  void drop() {
    if (index != null) {
      budget.release(index.charged());
      index = null;
    }
  }

  /** Cancels the build, drops the index and removes the listeners. */
  void dispose() {
    if (indexing != null) {
      indexing.cancel();
      indexing = null;
    }
    drop();
    indexListeners.clear();
  }

  /**
   * Changes the index in place for one file, or builds it again for a folder or link change, with
   * no index, or while a build runs.
   *
   * @param removed the entry that went, or null
   * @param added the file the index gains, or null
   * @param folder true when a folder or a link changed
   */
  void changed(Path removed, Path added, boolean folder) {
    FileIndex next =
        folder || index == null || indexing != null
            ? null
            : index.withChange(removed, added, budget);
    if (next == null) {
      build();
    } else {
      budget.release(index.charged());
      index = next;
      for (Runnable listener : new ArrayList<Runnable>(indexListeners)) {
        listener.run();
      }
    }
  }

  /** Builds the index of the workspace folder, canceling a running build. */
  void build() {
    if (indexing != null) {
      indexing.cancel();
    }
    if (workspace.root() == null || workspace.disposed()) {
      indexing = null;
      return;
    }
    final CancellationToken token = new CancellationToken();
    indexing = token;
    final Path folder = workspace.root();
    final WalkRules walk = workspace.rules();
    boolean started =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                FileIndex built = null;
                try {
                  built = FileIndex.build(folder, walk, FileIndex.MAX_FILES, budget, token);
                } catch (CancellationException e) {
                  built = null;
                } finally {
                  final FileIndex result = built;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          indexed(token, result);
                        }
                      });
                }
              }
            });
    if (!started) {
      indexing = null;
    }
  }

  /** Keeps a finished index when it is the newest build's; returns its charge otherwise. */
  private void indexed(CancellationToken token, FileIndex built) {
    if (workspace.disposed() || token != indexing) {
      if (built != null) {
        budget.release(built.charged());
      }
      return;
    }
    indexing = null;
    if (built == null) {
      return;
    }
    if (index != null) {
      budget.release(index.charged());
    }
    index = built;
    for (Runnable listener : new ArrayList<Runnable>(indexListeners)) {
      listener.run();
    }
  }
}
