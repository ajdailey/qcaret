package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;

/** A save found the target changed since the document's baseline stamp; nothing was written. */
public final class ExternalModificationException extends IOException {
  private static final long serialVersionUID = 1L;

  private final transient FileStamp current;

  /**
   * Creates the exception.
   *
   * @param file the target, for the message
   * @param current the target's stamp now
   */
  public ExternalModificationException(String file, FileStamp current) {
    super("Changed by another program: " + file);
    this.current = current;
  }

  /**
   * Returns the target's stamp at the save.
   *
   * @return the stamp
   */
  public FileStamp current() {
    return current;
  }
}
