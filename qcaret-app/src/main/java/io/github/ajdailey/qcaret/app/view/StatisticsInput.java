package io.github.ajdailey.qcaret.app.view;

/** The Document Statistics dialog's controller as its view calls it. Called on the UI thread. */
public interface StatisticsInput {
  /** Counts the selected document and its selections again, canceling a running count. */
  void refresh();

  /** Cancels a running count; the dialog has closed. */
  void closed();
}
