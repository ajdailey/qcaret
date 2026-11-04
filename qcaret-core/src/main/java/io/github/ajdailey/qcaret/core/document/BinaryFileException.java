package io.github.ajdailey.qcaret.core.document;

import java.io.IOException;

/** A load found a binary file and was not allowed to open one; nothing was decoded. */
public final class BinaryFileException extends IOException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param file the file name, for the message
   */
  public BinaryFileException(String file) {
    super("Binary file: " + file);
  }
}
