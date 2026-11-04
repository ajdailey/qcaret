package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.io.OutputStream;

/** Writes a file's content for {@link SafeSave}. */
public interface ContentWriter {
  /**
   * Writes the whole content. The stream is not closed by the writer.
   *
   * @param out the destination
   * @throws IOException when writing fails
   * @throws java.util.concurrent.CancellationException when the save is canceled
   */
  void write(OutputStream out) throws IOException;
}
