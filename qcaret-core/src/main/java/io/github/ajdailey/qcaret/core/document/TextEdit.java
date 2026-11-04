package io.github.ajdailey.qcaret.core.document;

/**
 * One applied replacement of a {@link DocumentChange}, in the coordinates of the text before the
 * change. Line counts are the line breaks in the removed and inserted text taken on their own.
 *
 * @param offset the first offset replaced
 * @param removed the removed text
 * @param inserted the inserted text
 * @param startLine the line holding {@code offset}
 * @param removedLines line breaks in {@code removed}
 * @param insertedLines line breaks in {@code inserted}
 */
public record TextEdit(
    int offset,
    String removed,
    String inserted,
    int startLine,
    int removedLines,
    int insertedLines) {}
