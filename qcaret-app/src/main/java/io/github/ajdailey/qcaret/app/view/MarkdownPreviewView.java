package io.github.ajdailey.qcaret.app.view;

/**
 * One tab's Markdown preview pane: display requests only. Called on the UI thread, after the work
 * behind each call has ended; the pane applies, never computes or decides.
 */
public interface MarkdownPreviewView {
  /**
   * Shows a mode: hides or shows the editor and the preview.
   *
   * @param mode the mode
   */
  void showMode(MarkdownViewMode mode);

  /**
   * Replaces the preview's content, keeping the scroll position where it can.
   *
   * @param content the render of the document's current revision and its code colors
   */
  void showRendered(MarkdownPreviewContent content);

  /**
   * Shows that the document is too large to render on a switch or an edit, with an action that
   * calls {@link MarkdownPreviewInput#renderRequested}. A render shown before stays below the
   * notice.
   *
   * @param chars the document's length
   * @param limit the longest document rendered on a switch or an edit
   */
  void showSizeNotice(int chars, int limit);

  /**
   * Scrolls the preview so a rendered line is at the top.
   *
   * @param line a line of the render shown
   */
  void scrollToLine(int line);
}
