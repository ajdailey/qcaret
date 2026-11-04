package io.github.ajdailey.qcaret.core.buffer;

/** Receives text from {@link TextContent#readChunks} one internal chunk at a time. */
public interface ChunkVisitor {
  /**
   * Receives the chars of {@code chunk} in {@code [start, end)}. The chunk is valid only during the
   * call and must not be modified or retained.
   *
   * @param chunk the chunk
   * @param start the first index in the chunk
   * @param end the index after the last char
   */
  void chunk(CharSequence chunk, int start, int end);
}
