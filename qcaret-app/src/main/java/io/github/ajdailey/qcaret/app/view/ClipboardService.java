package io.github.ajdailey.qcaret.app.view;

/** The system clipboard. Called on the UI thread. */
public interface ClipboardService {
  /**
   * Replaces the clipboard contents.
   *
   * @param text the text
   */
  void setText(String text);

  /**
   * Returns the clipboard's text.
   *
   * @return the text, or null when the clipboard holds none
   */
  String clipboardText();
}
