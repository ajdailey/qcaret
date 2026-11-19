package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.SelectionSet;

/**
 * The outcome of {@link Formats#run}.
 *
 * @param batch the {@link EditBatch.Kind#COMMAND} replacements; null for Check, after an error, or
 *     when nothing changes
 * @param after the selections after the batch; null with no batch
 * @param error the first error; null when every range is well-formed and fit the output limit
 * @param comments true when a JSON range holds a comment
 * @param fragment true when an XML selection was parsed as a fragment, not one document
 */
public record FormatResult(
    EditBatch batch, SelectionSet after, FormatError error, boolean comments, boolean fragment) {}
