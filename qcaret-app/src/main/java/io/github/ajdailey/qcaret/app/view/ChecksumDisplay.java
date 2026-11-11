package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/** An open checksum list dialog, as its controller updates it. Call on the UI thread. */
public interface ChecksumDisplay {
  /**
   * Empties the list for a new run.
   *
   * @param source the files or the list the run reads, shown above the lines
   */
  void clear(String source);

  /**
   * Appends lines.
   *
   * @param lines the lines, in order
   */
  void addLines(List<String> lines);

  /**
   * Shows a running run with Cancel.
   *
   * @param text what runs
   * @param percent the share of the bytes read, 0 to 100
   */
  void showProgress(String text, int percent);

  /**
   * Hides the progress and shows a status line.
   *
   * @param text the totals or a message
   */
  void showStatus(String text);
}
