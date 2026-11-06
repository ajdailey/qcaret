package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import java.util.List;

/**
 * One print job on a chosen printer. {@link #start}, {@link #page} and {@link #finish} run in that
 * order on one platform worker thread, and {@link #close} ends the job on that thread. A failing
 * call throws {@link PrintFailure}.
 */
public interface PrintTarget {
  /**
   * The text area of a page.
   *
   * @param columns the columns a row holds, line numbers included
   * @param rows the rows a page holds
   */
  record Frame(int columns, int rows) {}

  /**
   * A run of text with one role.
   *
   * @param text the chars, tabs expanded
   * @param column the row column it starts at
   * @param role its token role, or null for plain text
   */
  record Span(String text, int column, TokenRole role) {}

  /**
   * One row of a page.
   *
   * @param line the one-based line number to print, or 0 for none
   * @param spans the text
   */
  record Row(int line, List<Span> spans) {}

  /**
   * One page.
   *
   * @param number the page, from 1
   * @param total the page count of the whole layout, also when a range prints fewer
   * @param gutter the columns the line numbers take, 0 for none
   * @param rows the rows
   */
  record Page(int number, int total, int gutter, List<Row> rows) {}

  /** A printer or toolkit failure in a print job. */
  final class PrintFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what failed
     * @param cause the toolkit's error, or null
     */
    public PrintFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Reports whether the user chose to print the selection.
   *
   * @return true for the selection
   */
  boolean selectionOnly();

  /**
   * Returns the first page the user chose.
   *
   * @return from 1
   */
  int firstPage();

  /**
   * Returns the last page the user chose.
   *
   * @return the page, {@link Integer#MAX_VALUE} for all
   */
  int lastPage();

  /**
   * Starts the job and measures a page.
   *
   * @param name the document's name, for the job and the page header
   * @return the page's text area
   */
  Frame start(String name);

  /**
   * Prints a page.
   *
   * @param page the page
   */
  void page(Page page);

  /** Ends the job, handing it to the print system. */
  void finish();

  /**
   * Releases the job's resources and discards a job not finished. Call once in every outcome, also
   * without {@link #start}; throws nothing.
   */
  void close();
}
