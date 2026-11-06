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

  /** The user asked to close every other tab from this tab's menu. */
  void closeOthersRequested();

  /**
   * Closes the tabs on one side of this tab, asking for each dirty one.
   *
   * @param left true for the tabs to the left
   */
  void closeSideRequested(boolean left);

  /**
   * The user asked to copy the tab's file path from its menu.
   *
   * @param nameOnly true for the file name without its folder
   */
  void copyPathRequested(boolean nameOnly);

  /**
   * One of the tab's editor views took the focus.
   *
   * @param editor the input of the focused view
   */
  void viewFocused(EditorInput editor);
}
