package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/** The workspace browser's model as its view calls it. Called on the UI thread. */
public interface WorkspaceInput {
  /**
   * Returns the line above the tree.
   *
   * @return the folder, or how to set one
   */
  String title();

  /**
   * Returns the shown folder's row: the workspace folder, or the folder browsed to.
   *
   * @return the root directory, or null without either
   */
  WorkspaceNode rootNode();

  /**
   * Returns a directory's rows as listed so far.
   *
   * @param directory a directory row or {@link #rootNode()}
   * @return its entries, then a {@link WorkspaceNode.Kind#MORE} row when more are listed than
   *     shown; a single {@link WorkspaceNode.Kind#LOADING}, {@link WorkspaceNode.Kind#EMPTY} or
   *     {@link WorkspaceNode.Kind#ERROR} row; empty before {@link #expand} was called
   */
  List<WorkspaceNode> children(WorkspaceNode directory);

  /**
   * Lists a directory on a worker unless listed; the view hears {@link
   * WorkspaceView#workspaceChanged} when its rows change.
   *
   * @param directory a directory row
   */
  void expand(WorkspaceNode directory);

  /**
   * Acts on a row: a file opens, a {@link WorkspaceNode.Kind#MORE} row shows more entries.
   *
   * @param node the row
   */
  void activate(WorkspaceNode node);

  /** Lists the expanded directories again and rebuilds the file index. */
  void refresh();

  /**
   * Makes a directory the workspace folder.
   *
   * @param directory a directory row
   */
  void setWorkspace(WorkspaceNode directory);

  /**
   * Copies a row's path to the clipboard.
   *
   * @param node a directory or file row
   */
  void copyPath(WorkspaceNode node);

  /**
   * Opens a file in the application the system associates with it, unless the system would run it.
   *
   * @param node a file row
   */
  void openInDefaultApplication(WorkspaceNode node);

  /**
   * Returns the row menu's file operations.
   *
   * @return Search in Folder, New File, New Folder, Rename and Delete
   */
  WorkspaceFilesInput files();

  /**
   * Returns the navigation, filter and sort.
   *
   * @return the path field, Parent, Home, Favorites, Workspace, the filter, hidden entries and the
   *     sort column
   */
  WorkspaceBrowseInput browse();

  /** Lists the selected document's directories and selects its row. */
  void revealActive();

  /** Hides the browser. */
  void hide();
}
