package io.github.ajdailey.qcaret.core.document;

/** Receives a {@link Document}'s changes on the thread that edits it. */
public interface DocumentListener {
  /**
   * Called after a text change, before selections are reported.
   *
   * @param change the change
   */
  void textChanged(DocumentChange change);

  /**
   * Called when the document turns dirty or clean.
   *
   * @param dirty the new state
   */
  void dirtyChanged(boolean dirty);
}
