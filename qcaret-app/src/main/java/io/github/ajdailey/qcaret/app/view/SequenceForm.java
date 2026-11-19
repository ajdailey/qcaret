package io.github.ajdailey.qcaret.app.view;

/**
 * The fields of the Insert Number Sequence dialog.
 *
 * @param start the start field's text
 * @param step the step field's text
 * @param width the zero-padded width
 * @param base the chosen base: 0 decimal, 1 hex upper case, 2 hex lower case
 */
public record SequenceForm(String start, String step, int width, int base) {}
