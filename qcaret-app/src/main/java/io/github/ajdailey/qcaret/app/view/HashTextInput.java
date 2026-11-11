package io.github.ajdailey.qcaret.app.view;

/** A Tools > Generate dialog's controller as its view calls it. Called on the UI thread. */
public interface HashTextInput {
  /**
   * Hashes the field's new text.
   *
   * @param text the field's text
   */
  void textChanged(String text);

  /** Copies the shown hash to the clipboard. */
  void copy();

  /** Drops a running hash; the dialog has closed. */
  void closed();
}
