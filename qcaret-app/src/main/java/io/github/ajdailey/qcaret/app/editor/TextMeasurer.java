package io.github.ajdailey.qcaret.app.editor;

/** Measures rendered text in pixels for a layout computed without the toolkit. */
public interface TextMeasurer {
  /**
   * Measures one line of text.
   *
   * @param text the text, without line breaks
   * @param strong true for bold text, such as a table's header row
   * @return the advance width in pixels
   */
  int width(String text, boolean strong);
}
