package io.github.ajdailey.qcaret.app.view;

import java.util.List;

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
