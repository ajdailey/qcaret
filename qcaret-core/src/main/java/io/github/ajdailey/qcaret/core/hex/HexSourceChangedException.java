package io.github.ajdailey.qcaret.core.hex;

import java.io.IOException;

/** A {@link FileHexSource} found the file's stamp changed since it opened; the read was dropped. */
public final class HexSourceChangedException extends IOException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message names the file
   */
  public HexSourceChangedException(String message) {
    super(message);
  }
}
