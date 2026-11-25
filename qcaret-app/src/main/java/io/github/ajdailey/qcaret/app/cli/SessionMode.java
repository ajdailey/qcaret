package io.github.ajdailey.qcaret.app.cli;

/** Whether a start restores the previous session. */
public enum SessionMode {
  /** No option: restores unless the command line names a comparison. */
  DEFAULT,
  /**
   * {@code --session}: restores, also with a comparison, and opens the session's windows with
   * {@code session.restore} off.
   */
  RESTORE,
  /** {@code --no-session}: starts without the previous session. */
  SKIP
}
