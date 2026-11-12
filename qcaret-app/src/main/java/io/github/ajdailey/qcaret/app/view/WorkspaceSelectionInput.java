package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * The workspace browser's row menu and keys over the tree's selection, as its view calls them. The
 * view passes the selected rows and the focused row; an empty selection stands for the shown
 * folder. Called on the UI thread.
 */
public interface WorkspaceSelectionInput {
  /** A row menu item or key. */
  enum Action {
    /** Opens every selected file. */
    OPEN,
    /** Opens every selected file in the system's application. */
    OPEN_DEFAULT,
    /** Makes the focused directory the workspace folder. */
    SET_FOLDER,
    /** Copies every selected path, one per line. */
    COPY_PATH,
    /** Searches the one selected folder, or the one selected file's folder. */
    SEARCH_FOLDER,
    /** Creates a file in the focused directory. */
    NEW_FILE,
    /** Creates a folder in the focused directory. */
    NEW_FOLDER,
    /** Renames the one selected entry. */
    RENAME,
    /** Deletes every selected entry after one prompt. */
    DELETE
  }

  /**
   * Reports whether an action applies to a selection.
   *
   * @param action the action
   * @param selected the selected rows, in tree order; empty for the shown folder
   * @param focused the focused row among them, or null for the first
   * @return true when it applies
   */
  boolean enabled(Action action, List<WorkspaceNode> selected, WorkspaceNode focused);

  /**
   * Runs an action on a selection; does nothing where {@link #enabled} is false.
   *
   * @param action the action
   * @param selected the selected rows, in tree order; empty for the shown folder
   * @param focused the focused row among them, or null for the first
   */
  void run(Action action, List<WorkspaceNode> selected, WorkspaceNode focused);
}
