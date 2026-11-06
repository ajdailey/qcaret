package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/** The recovery dialog's controller as its view calls it. Called on the UI thread. */
public interface RecoveryInput {
  /**
   * Returns the buffers left to decide.
   *
   * @return the rows
   */
  List<RecoveryRow> rows();

  /**
   * Opens buffers dirty with their recovered text; the original files are not written. A row goes
   * when its buffer is open; a damaged row stays.
   *
   * @param rows indexes into {@link #rows}
   */
  void restore(int[] rows);

  /**
   * Deletes buffers' recovery files.
   *
   * @param rows indexes into {@link #rows}
   */
  void discard(int[] rows);

  /** Keeps every row's file for the next start; the dialog closes. */
  void later();
}
