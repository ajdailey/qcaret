package io.github.ajdailey.qcaret.core.document;

import java.io.IOException;

/** Receives a {@link LoadPass}'s decoded chars. A piece may end anywhere. */
interface TextSink {
  /**
   * Takes {@code chars[from, to)}.
   *
   * @param chars the chars, valid during the call
   * @param from the first index
   * @param to the index after the last char
   * @throws IOException when the text passes {@link DocumentFiles#MAX_BYTES} chars
   */
  void append(char[] chars, int from, int to) throws IOException;

  /**
   * Returns the heap to charge so far ({@link LoadReservation#charge}).
   *
   * @return bytes
   */
  long heapBytes();
}
