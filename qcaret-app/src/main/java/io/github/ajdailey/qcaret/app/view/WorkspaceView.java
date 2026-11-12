package io.github.ajdailey.qcaret.app.view;

import java.util.List;
import java.util.function.Consumer;

/** The workspace browser dock and the Quick Open popup. Called on the UI thread. */
public interface WorkspaceView {
  /**
   * Shows the workspace browser over its model, or hides it.
   *
   * @param input the browser's model
   * @param shown true to show
   */
  void showWorkspace(WorkspaceInput input, boolean shown);

  /**
   * Reports that a directory's rows changed.
   *
   * @param directory the directory, or null when the folder or its whole tree changed
   */
  void workspaceChanged(WorkspaceNode directory);

  /**
   * Expands each directory of a chain, listed already, and selects its last row.
   *
   * @param chain directories below the root, then the row to select
   */
  void revealWorkspace(List<WorkspaceNode> chain);

  /**
   * Asks for a file or folder name in a one-line field. OK passes the text; Cancel, Esc and closing
   * pass null. The answer runs once.
   *
   * @param title the window title
   * @param message the line above the field
   * @param name the field's text, selected up to {@code selectEnd}
   * @param selectEnd the end of the selection in {@code name}
   * @param answer receives the text, or null
   */
  void askFileName(
      String title, String message, String name, int selectEnd, Consumer<String> answer);

  /**
   * Asks before a permanent delete, with Cancel the default.
   *
   * @param message what goes, and that it cannot be undone
   * @param delete runs when the user confirms
   */
  void askDelete(String message, Runnable delete);

  /**
   * Asks before opening many files in their default applications, with Cancel the default.
   *
   * @param count the files
   * @param open runs when the user confirms
   */
  void askOpenMany(int count, Runnable open);

  /**
   * Shows the Quick Open popup: a filter field over the rows {@link QuickOpenInput#rows} returns.
   *
   * @param input the popup's controller
   */
  void showQuickOpen(QuickOpenInput input);

  /** Reports that the Quick Open rows or summary changed. */
  void quickOpenChanged();

  /**
   * Reports whether the system decides what a file runs by its extension (Windows).
   *
   * @return true on Windows
   */
  boolean launchesByExtension();
}
