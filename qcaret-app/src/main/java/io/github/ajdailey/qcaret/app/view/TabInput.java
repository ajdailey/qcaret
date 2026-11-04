package io.github.ajdailey.qcaret.app.view;

/** What a tab forwards to the document controller. Called on the UI thread. */
public interface TabInput {
  /** The user asked to close the tab: its close button or a middle click. */
  void closeRequested();

  /** The tab became the selected one. */
  void activated();

  /**
   * The user dragged the tab to another position in the strip.
   *
   * @param index its new index
   */
  void moved(int index);
}
