package io.github.ajdailey.qcaret.core.document;

/** Receives a {@link SelectionModel}'s changes on the document's thread. */
public interface SelectionListener {
  /**
   * Called after the selections changed, by an edit or a move.
   *
   * @param model the model
   */
  void selectionsChanged(SelectionModel model);
}
