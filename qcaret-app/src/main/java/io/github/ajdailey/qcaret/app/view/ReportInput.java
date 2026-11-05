package io.github.ajdailey.qcaret.app.view;

/**
 * A replace or restore run's report: one row per file, filled while the run goes on. Rows are read
 * again after each {@link SearchView#replaceReportChanged}. Called on the UI thread.
 */
public interface ReportInput {
  /**
   * One file's row.
   *
   * @param file the file's name
   * @param folder its folder, empty for an untitled document
   * @param result what happened, with the reason or the error
   * @param failed true for a failure or a skip, which the view marks
   */
  record Row(String file, String folder, String result, boolean failed) {}

  /**
   * Returns the window title.
   *
   * @return the title
   */
  String title();

  /**
   * Returns the counts line: changed, skipped, failed and not attempted, or the progress.
   *
   * @return the line
   */
  String summary();

  /**
   * Counts the rows.
   *
   * @return the count
   */
  int rowCount();

  /**
   * Returns a row.
   *
   * @param row the index
   * @return the row
   */
  Row row(int row);

  /**
   * Reports whether the run goes on.
   *
   * @return true until it ends
   */
  boolean running();

  /** Stops the run between two files. */
  void cancel();

  /**
   * Reports whether the run kept backups that Restore Run can write back.
   *
   * @return true for a finished replace run with backups
   */
  boolean canRestore();

  /** Restores the run's backups, in a report of its own. */
  void restore();
}
