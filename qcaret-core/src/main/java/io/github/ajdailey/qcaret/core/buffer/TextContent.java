package io.github.ajdailey.qcaret.core.buffer;

/**
 * Read access to text with a line index. Line breaks are LF, CR LF and CR; lines are zero-based. A
 * CR LF pair is one break, also when it spans two internal chunks.
 */
public interface TextContent extends CharSequence {
  /**
   * Returns the number of lines: the number of line breaks plus one.
   *
   * @return the line count, at least 1
   */
  int lineCount();

  /**
   * Returns the line holding an offset. An offset between the CR and the LF of a CR LF pair belongs
   * to the line the pair ends.
   *
   * @param offset an offset from 0 to {@link #length()}
   * @return the line index
   * @throws IndexOutOfBoundsException when the offset is out of range
   */
  int lineAt(int offset);

  /**
   * Returns the offset of a line's first char.
   *
   * @param line a line index from 0 to {@code lineCount() - 1}
   * @return the start offset
   * @throws IndexOutOfBoundsException when the line is out of range
   */
  int offsetOfLine(int line);

  /**
   * Passes the text in {@code [start, end)} to a visitor as consecutive internal chunks, in order.
   *
   * @param start the first offset
   * @param end the offset after the last char
   * @param visitor receives each chunk
   * @throws IndexOutOfBoundsException when the range is out of bounds
   */
  void readChunks(int start, int end, ChunkVisitor visitor);

  /**
   * Returns the length of a line without its line break.
   *
   * @param line a line index from 0 to {@code lineCount() - 1}
   * @return the char count
   * @throws IndexOutOfBoundsException when the line is out of range
   */
  int lineLength(int line);

  /**
   * Returns a line's text without its line break, copied.
   *
   * @param line a line index from 0 to {@code lineCount() - 1}
   * @return the line text
   * @throws IndexOutOfBoundsException when the line is out of range
   */
  String line(int line);

  /**
   * Positions {@code view} on a line's text without its line break. No chars are copied; the view
   * reads this text version until it is positioned again.
   *
   * @param line a line index from 0 to {@code lineCount() - 1}
   * @param view the view to position
   * @return {@code view}
   * @throws IndexOutOfBoundsException when the line is out of range
   */
  LineView lineView(int line, LineView view);

  /**
   * Returns a cursor for sequential reads of this text version.
   *
   * @param offset the starting offset, from 0 to {@link #length()}
   * @return the cursor
   * @throws IndexOutOfBoundsException when the offset is out of range
   */
  TextCursor cursor(int offset);
}
