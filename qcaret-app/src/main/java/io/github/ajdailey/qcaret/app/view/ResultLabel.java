package io.github.ajdailey.qcaret.app.view;

/**
 * One row of the results dock.
 *
 * @param text the row's text
 * @param highlightStart where the highlighted match text starts in {@code text}, or -1
 * @param highlightEnd where it ends
 * @param stale true when the row's source changed since the search
 */
public record ResultLabel(String text, int highlightStart, int highlightEnd, boolean stale) {
  /**
   * Creates a row without highlighted text.
   *
   * @param text the text
   * @param stale true when the source changed
   * @return the row
   */
  public static ResultLabel plain(String text, boolean stale) {
    return new ResultLabel(text, -1, -1, stale);
  }
}
