package io.github.ajdailey.qcaret.core.buffer;

/**
 * Mutable text with a line index. Edits and reads of the storage itself come from one thread; a
 * {@link TextSnapshot} is for other threads.
 */
public interface TextStorage extends TextContent {
  /**
   * Replaces {@code removeLength} chars at {@code offset} with {@code text}.
   *
   * @param offset the first offset replaced
   * @param removeLength the number of chars removed
   * @param text the inserted text, possibly empty
   * @throws IndexOutOfBoundsException when the range is out of bounds
   */
  void replace(int offset, int removeLength, CharSequence text);

  /**
   * Returns an immutable view of the current text that later edits do not change. The cost is
   * implementation-specific and documented by each implementation.
   *
   * @return the snapshot
   */
  TextSnapshot snapshot();
}
