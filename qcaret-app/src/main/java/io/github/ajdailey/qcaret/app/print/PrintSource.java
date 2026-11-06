package io.github.ajdailey.qcaret.app.print;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.syntax.Syntax;

/**
 * What a print job reads, captured when the command runs.
 *
 * @param name the document's name, for the header
 * @param text the text
 * @param selections {@code start, end} offset pairs of the non-empty selections, ascending
 * @param tabWidth columns per tab stop
 * @param lineNumbers true to number the lines
 * @param syntax the grammar, or null for plain text
 * @param maxLineChars lines longer than this get no tokens
 */
public record PrintSource(
    String name,
    TextSnapshot text,
    int[] selections,
    int tabWidth,
    boolean lineNumbers,
    Syntax syntax,
    int maxLineChars) {}
