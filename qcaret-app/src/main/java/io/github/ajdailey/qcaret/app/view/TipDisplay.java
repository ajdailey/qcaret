package io.github.ajdailey.qcaret.app.view;

/** The open Tip of the Day dialog, as its controller updates it. Call on the UI thread. */
public interface TipDisplay {
  /**
   * Shows a tip.
   *
   * @param text the tip
   * @param onStartup the check box's state
   */
  void showTip(String text, boolean onStartup);

  /** Brings the dialog to the front. */
  void toFront();
}
