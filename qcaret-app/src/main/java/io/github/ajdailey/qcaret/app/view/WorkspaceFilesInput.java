package io.github.ajdailey.qcaret.app.view;

/**
 * The workspace browser's row menu file operations as its view calls them. Called on the UI thread.
 */
public interface WorkspaceFilesInput {
  /**
   * Opens HyperSearch with the scope set to a folder and its subdirectories.
   *
   * @param node a directory row, or a file row for its directory
   */
  void searchInFolder(WorkspaceNode node);

  /**
   * Asks a name and creates an empty file in a directory, then opens it.
   *
   * @param directory a directory row or {@link WorkspaceInput#rootNode()}
   */
  void newFile(WorkspaceNode directory);

  /**
   * Asks a name and creates a folder in a directory.
   *
   * @param directory a directory row or {@link WorkspaceInput#rootNode()}
   */
  void newFolder(WorkspaceNode directory);

  /**
   * Asks a new name for a file or folder in the same directory; open documents follow.
   *
   * @param node a file or directory row below the root
   */
  void rename(WorkspaceNode node);

  /**
   * Asks, then deletes a file or folder permanently; open documents under it stay, marked missing.
   *
   * @param node a file or directory row below the root
   */
  void delete(WorkspaceNode node);
}
