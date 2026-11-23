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

  /**
   * Selects rendered text of the content shown and scrolls it into view, without taking the focus.
   * Browser content ignores it.
   *
   * @param start the text's first rendered offset
   * @param end the rendered offset after the text
   */
  void selectRendered(int start, int end);

  /**
   * Returns a decoder for the content shown, holding the pane's width now.
   *
   * @return the decoder
   */
  MarkdownImageDecoder imageDecoder();

  /**
   * Draws a decoded image in the space its request reserved.
   *
   * @param request one of the shown content's {@link MarkdownPreviewContent#images}
   * @param image the image, or null for a placeholder: an SVG, a file too large or unreadable, or
   *     bytes that decode to no image
   */
  void showImage(MarkdownImageRequest request, DecodedImage image);

  /**
   * Scrolls Browser content to the element with an id: a heading's anchor, a footnote or an id in
   * the document's own HTML. Basic content ignores it.
   *
   * @param id the id, percent-decoded
   */
  void scrollToAnchor(String id);

  /**
   * Sets the size of Basic content's text; Browser content takes its size from its page. Images
   * keep their size.
   *
   * @param percent the size in percent of the normal size
   */
  void setZoom(int percent);
}
