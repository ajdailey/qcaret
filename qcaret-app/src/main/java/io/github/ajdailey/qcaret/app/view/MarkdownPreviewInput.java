package io.github.ajdailey.qcaret.app.view;

/**
 * Input events a {@link MarkdownPreviewView} reports to its tab's preview controller. UI thread.
 */
public interface MarkdownPreviewInput {
  /**
   * Connects the pane; the current mode and render, if any, are shown at once.
   *
   * @param view the pane, or null to disconnect it
   */
  void attach(MarkdownPreviewView view);

  /** Reports the notice's render action. */
  void renderRequested();

  /**
   * Reports the preview's new top line.
   *
   * @param line the rendered line at the top
   */
  void previewScrolled(int line);

  /**
   * Reports a click on rendered text, which follows a link or footnote reference there.
   *
   * @param offset the rendered offset clicked
   */
  void clicked(int offset);

  /**
   * Reports a navigation the Browser preview canceled after a click or key on it, which follows the
   * link: {@code about:blank#id} scrolls to the id, a {@code file:} URL opens in QCaret, an {@code
   * http}, {@code https}, {@code mailto} or {@code ftp} URL opens in the system browser.
   *
   * @param location the location the browser was about to load
   */
  void linkFollowed(String location);

  /** Reports that the Browser preview failed to start; the preview shows Basic. */
  void browserFailed();
}
