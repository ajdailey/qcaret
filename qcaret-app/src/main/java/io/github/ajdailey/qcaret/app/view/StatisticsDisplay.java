package io.github.ajdailey.qcaret.app.view;

/** The open Document Statistics dialog, as its controller updates it. Call on the UI thread. */
public interface StatisticsDisplay {
  /**
   * Shows a running count.
   *
   * @param percent the share of the chars read, 0 to 100
   */
  void showProgress(int percent);

  /**
   * Shows the counts.
   *
   * @param report the counts
   */
  void showReport(StatisticsReport report);

  /**
   * Shows a message in place of the counts.
   *
   * @param message why no counts show
   */
  void showMessage(String message);

  /** Brings the dialog to the front. */
  void toFront();
}
