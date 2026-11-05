package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;

/**
 * An edit computed from a text and its selections: the batch to apply and the selections after it.
 *
 * @param batch the replacements, in the coordinates of the text the edit was computed from
 * @param after the selections after the batch, or null to map the current ones through it
 */
public record Edit(EditBatch batch, SelectionSet after) {}
