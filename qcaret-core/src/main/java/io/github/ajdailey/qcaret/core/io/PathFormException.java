package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;

/**
 * A path {@link SafePaths} rejects by its text, before any file system access. The message names
 * the reason for the log; {@link #reason} names it for the user's notice.
 */
public final class PathFormException extends IOException {
  private static final long serialVersionUID = 1L;

  /** Why a path's text is rejected. */
  public enum Reason {
    /** The text is empty. */
    EMPTY,
    /** The text contains a NUL character. */
    NUL,
    /** The path opens a Windows device ({@code DeviceNames.opensDevice}). */
    DEVICE,
    /** The file system does not accept the text as a path. */
    INVALID
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why the path is rejected
   * @param message the log message
   * @param cause the underlying failure, or null
   */
  public PathFormException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /**
   * Returns why the path is rejected.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
