package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.BitSet;

/**
 * The bytes a hex view shows, addressed by {@code long} position. The size is fixed for the life of
 * a source; a file that changes on disk fails the next read with {@link HexSourceChangedException}
 * instead. Safe to read from several threads.
 */
public interface HexSource {
  /**
   * Returns the byte count.
   *
   * @return the size, at least 0
   */
  long size();

  /**
   * Returns the charset that Copy as Text decodes with and a text find pattern encodes with.
   *
   * @return the document's charset, or ISO-8859-1 for a file
   */
  Charset charset();

  /**
   * Reads {@code length} bytes into {@code dst[0, length)}. Blocks on file I/O: call it on a
   * worker.
   *
   * @param position the first byte
   * @param dst receives the bytes
   * @param length the byte count; {@code position + length} is at most {@link #size}
   * @param replaced when not null, has bit {@code i} set for each {@code dst[i]} that is part of
   *     the encoder's replacement for a char the charset cannot encode, and every other bit in
   *     {@code [0, length)} cleared
   * @throws HexSourceChangedException when the file changed since the source opened
   * @throws IOException when the read fails
   * @throws IndexOutOfBoundsException when the range is outside the source or {@code dst}
   */
  @WorkerThread
  void read(long position, byte[] dst, int length, BitSet replaced) throws IOException;
}
