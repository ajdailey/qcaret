package io.github.ajdailey.qcaret.app.view;

/** The Tip of the Day dialog's controller as its view calls it. Called on the UI thread. */
public interface TipInput {
  /** Shows the next tip. */
  void nextTip();

  /**
   * Sets whether the dialog opens at startup.
   *
   * @param on the check box's state
   */
  void showOnStartup(boolean on);

  /** The dialog has closed. */
  void closed();
}
