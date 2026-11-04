package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;

/**
 * An immutable version of a document for a worker: a save, a search, a highlighter.
 *
 * @param revision the revision it was taken at
 * @param state the undo state it was taken at, which {@link Document#markSaved} records
 * @param text the text
 * @param encoding the encoding a save writes
 * @param lineEnding the line ending Enter inserts
 */
public record DocumentSnapshot(
    long revision, long state, TextSnapshot text, TextEncoding encoding, LineEnding lineEnding) {}
