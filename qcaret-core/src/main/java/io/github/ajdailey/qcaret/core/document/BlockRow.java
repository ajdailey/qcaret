package io.github.ajdailey.qcaret.core.document;

/**
 * One line of a {@link BlockSelection}.
 *
 * @param line the line index
 * @param start the offset of the block's left edge on the line, or the line end when shorter
 * @param end the offset of the right edge, or the line end when shorter
 * @param padding spaces typing inserts before the text so it lands at the left column, 0 unless the
 *     line ends left of it
 */
public record BlockRow(int line, int start, int end, int padding) {}
