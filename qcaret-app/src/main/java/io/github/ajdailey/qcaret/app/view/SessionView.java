package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/** The window's sashes, the browser's expanded rows and the recovery dialog. On the UI thread. */
public interface SessionView {
  /**
   * Returns the sash weights of the workspace browser and the editors.
   *
   * @return two weights
   */
  int[] sideWeights();

  /**
   * Sets the sash weights of the workspace browser and the editors.
   *
   * @param weights two positive weights
   */
  void setSideWeights(int[] weights);

  /**
   * Returns the sash weights of the editors and the docks below them.
   *
   * @return two weights
   */
  int[] dockWeights();

  /**
   * Sets the sash weights of the editors and the docks below them.
   *
   * @param weights two positive weights
   */
  void setDockWeights(int[] weights);

  /**
   * Returns the weights of the results and Bookmarks docks.
   *
   * @return two positive weights
   */
  int[] resultsWeights();

  /**
   * Sets the weights of the results and Bookmarks docks.
   *
   * @param weights two positive weights
   */
  void setResultsWeights(int[] weights);

  /**
   * Returns the workspace browser's expanded rows.
   *
   * @return the directories, parents first
   */
  List<WorkspaceNode> expandedWorkspace();

  /**
   * Expands a listed directory's row; a row not showing is left alone.
   *
   * @param directory the row
   */
  void expandWorkspace(WorkspaceNode directory);

  /**
   * Shows the recovery dialog.
   *
   * @param input the dialog's controller
   */
  void showRecovery(RecoveryInput input);

  /** Reports that the recovery rows changed; the dialog closes when none is left. */
  void recoveryChanged();
}
