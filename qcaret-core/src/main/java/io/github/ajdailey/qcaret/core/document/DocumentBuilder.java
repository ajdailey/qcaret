package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;

/**
 * Collects a document's text in pieces, straight into rope leaves, and counts its line endings. A
 * piece may end anywhere, between a CR and an LF or inside a surrogate pair.
 */
public final class DocumentBuilder implements TextSink {
  private final RopeStorage.Builder text = new RopeStorage.Builder();
  private final LineEndingStats.Counter endings = new LineEndingStats.Counter();

  /**
   * Appends {@code chars[from, to)}.
   *
   * @param chars the chars, copied
   * @param from the first index
   * @param to the index after the last char
   * @throws IOException when the text passes {@link DocumentFiles#MAX_BYTES} chars
   */
  @Override
  public void append(char[] chars, int from, int to) throws IOException {
    if (text.length() + (to - from) > DocumentFiles.MAX_BYTES) {
      throw new IOException("Text too large: over " + DocumentFiles.MAX_BYTES + " chars");
    }
    text.append(chars, from, to);
    endings.add(chars, from, to);
  }

  /**
   * Returns the heap the text takes so far ({@link RopeStorage.Builder#heapBytes}).
   *
   * @return bytes
   */
  @Override
  public long heapBytes() {
    return text.heapBytes();
  }

  /**
   * Returns what a document of this text holds while open: the text and undo headroom of the same
   * size, at most {@code limits.maxBytes()}.
   *
   * @param limits the document's undo bounds
   * @return bytes
   */
  public long retainedBytes(UndoLimits limits) {
    long heap = heapBytes();
    return heap + Math.min(limits.maxBytes(), heap);
  }

  /**
   * Creates the document; this builder is not used afterwards.
   *
   * @param encoding the encoding a save writes
   * @param empty the line ending of a text without breaks
   * @param limits the undo history bounds
   * @return the document, as {@link Document#of} creates it
   */
  public Document build(TextEncoding encoding, LineEnding empty, UndoLimits limits) {
    return new Document(text.build(), endings.stats(), encoding, empty, limits, System::nanoTime);
  }
}
