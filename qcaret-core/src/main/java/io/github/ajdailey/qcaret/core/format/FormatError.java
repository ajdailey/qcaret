package io.github.ajdailey.qcaret.core.format;

/**
 * Why a range did not format: the first error found, in the coordinates of the whole text.
 *
 * @param offset the error's offset
 * @param line its line, from 1
 * @param column its UTF-16 code unit in the line, from 1
 * @param reason the parser's message
 */
public record FormatError(int offset, int line, int column, String reason) {}
