package io.github.ajdailey.qcaret.app.view;

/** The Outline dock. Called on the UI thread. */
public interface OutlineView {
  /**
   * Shows the Outline dock over its model, or hides it.
   *
   * @param input the dock's model
   * @param shown true to show
   */
  void showOutline(OutlineInput input, boolean shown);

  /** Reports a change of the entries or the summary. */
  void outlineChanged();

  /**
   * Selects the entry holding the caret.
   *
   * @param index the entry's index, or -1 for none
   */
  void outlineSelected(int index);
}
