package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves one window's workspace folder and folder browsed to with a rename, and clears them after a
 * delete. Confined to the UI thread.
 */
final class WorkspaceFollow {
  private final WorkspaceController workspace;
  private final MainView view;

  WorkspaceFollow(WorkspaceController workspace, MainView view) {
    this.workspace = workspace;
    this.view = view;
  }

  /**
   * Moves the workspace folder and the folder browsed to after a rename of either or a parent, in
   * every window ({@link #follow}).
   *
   * @param move the rename
   */
  void moved(PathMove move) {
    BrowserWindows peers = workspace.peers();
    if (peers == null) {
      follow(move);
    } else {
      peers.moved(move);
    }
  }

  /**
   * Moves this window's workspace folder and folder browsed to with a rename: the index is built
   * again under the new path, the browser lists it again with the expanded directories moved, and
   * the session is written.
   *
   * @param move the rename
   */
  void follow(PathMove move) {
    Path root = workspace.root();
    Path browsed = workspace.browsed();
    Path nextRoot = move.real(root);
    Path nextBrowsed = move.real(browsed);
    if (workspace.disposed() || (nextRoot == root && nextBrowsed == browsed)) {
      return;
    }
    List<Path> expanded = new ArrayList<Path>();
    for (String directory : workspace.expandedDirectories()) {
      expanded.add(move.real(Path.of(directory)));
    }
    if (nextRoot != root) {
      view.showNotice(UiText.WORKSPACE_MOVED.format(nextRoot));
    }
    workspace.relocate(nextRoot, nextBrowsed, expanded);
  }

  /**
   * Clears the workspace folder, in every window, after a delete of it or a parent ({@link
   * #clearUnder}).
   *
   * @param entry the deleted entry
   */
  void deleted(BrowserPath entry) {
    BrowserWindows peers = workspace.peers();
    if (peers == null) {
      clearUnder(entry);
    } else {
      peers.deleted(entry);
    }
  }

  /**
   * Clears this window's workspace folder with a notice when a deleted entry held it; a folder
   * browsed to under the entry goes with it. The index is dropped and the session written.
   *
   * @param entry the deleted entry
   */
  void clearUnder(BrowserPath entry) {
    Path root = workspace.root();
    Path browsed = workspace.browsed();
    if (workspace.disposed()
        || !(BrowserPath.under(root, entry.real()) || BrowserPath.under(root, entry.path()))) {
      return;
    }
    view.showNotice(UiText.WORKSPACE_DELETED.format(root));
    boolean browsedGone =
        BrowserPath.under(browsed, entry.real()) || BrowserPath.under(browsed, entry.path());
    workspace.relocate(null, browsedGone ? null : browsed, List.of());
  }
}
