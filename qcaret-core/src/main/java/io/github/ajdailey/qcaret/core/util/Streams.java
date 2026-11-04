package io.github.ajdailey.qcaret.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded stream reads. */
public final class Streams {
  private static final int BUFFER_BYTES = 8192;

  private Streams() {}

  /**
   * Reads a stream to its end, refusing one longer than a limit. The caller closes the stream.
   *
   * @param in the stream
   * @param limit the most bytes accepted, at least 0
   * @return every byte read
   * @throws IOException on a read error, or when the stream holds more than {@code limit} bytes
   */
  public static byte[] readLimited(InputStream in, int limit) throws IOException {
    Checks.notNegative(limit, "limit");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_BYTES];
    long total = 0;
    int n = in.read(buffer);
    while (n >= 0) {
      total += n;
      if (total > limit) {
        throw new IOException("More than " + limit + " bytes");
      }
      out.write(buffer, 0, n);
      n = in.read(buffer);
    }
    return out.toByteArray();
  }
}
