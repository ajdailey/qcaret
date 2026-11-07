package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;

/**
 * A file's difference from an open document's text ({@link DocumentFiles#reload}).
 *
 * @param replacement the span between the common prefix and suffix, or null when the texts are
 *     equal
 * @param encoding the encoding the file decoded in
 * @param lineEnding the file's dominant line ending
 * @param baseline the file's stamp before it was read and the SHA-256 of the bytes read
 * @param roundTrips true when the file's text encodes back to its bytes
 */
public record ReloadedFile(
    Replacement replacement,
    TextEncoding encoding,
    LineEnding lineEnding,
    FileBaseline baseline,
    boolean roundTrips) {}
