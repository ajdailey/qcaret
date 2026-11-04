package io.github.ajdailey.qcaret.core.document;

import java.util.List;

/**
 * A text change {@link Document} applied: one per applied batch, undo and redo step. Applying the
 * edits highest offset first to the text before the change gives the text after it.
 *
 * @param revision the document revision after the change
 * @param cause what applied it
 * @param edits the replacements by ascending offset
 */
public record DocumentChange(long revision, Cause cause, List<TextEdit> edits) {
  /** What applied a change. */
  public enum Cause {
    /** {@link Document#apply}. */
    EDIT,
    /** {@link Document#undo}. */
    UNDO,
    /** {@link Document#redo}. */
    REDO
  }
}
