package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.List;

/**
 * An edit computed from a text and its selections: the batch to apply and the selections after it.
 *
 * @param batch the replacements, in the coordinates of the text the edit was computed from
 * @param after the selections after the batch, or null to map the current ones through it
 * @param unchanged the lines a line command left as they were because changing them would put a CR
 *     directly before an LF, joining two line breaks
 */
public record Edit(EditBatch batch, SelectionSet after, int unchanged) {
  /**
   * Creates an edit that leaves no line unchanged.
   *
   * @param batch the replacements
   * @param after the selections after the batch, or null
   */
  public Edit(EditBatch batch, SelectionSet after) {
    this(batch, after, 0);
  }

  /**
   * Returns an edit that changes nothing because every change would join two line breaks.
   *
   * @param lines the lines left unchanged
   * @return the edit, with no replacements
   */
  public static Edit unchanged(int lines) {
    return new Edit(EditBatch.of(EditBatch.Kind.COMMAND, List.of()), null, lines);
  }
}
