package io.github.ajdailey.qcaret.app.editor;

/** Measures rendered text in pixels for a layout computed without the toolkit. */
public interface TextMeasurer {
  /**
   * Measures a range of one line of the rendered text, each span in the font it is drawn with.
   *
   * @param start the range's first offset in the rendered text
   * @param end the offset after the range, on the same line
   * @param strong true for bold text, such as a table's header row
   * @return the advance width in pixels
   */
  int width(int start, int end, boolean strong);
}
