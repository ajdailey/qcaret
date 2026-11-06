package io.github.ajdailey.qcaret.app.cli;

/** Whether a start restores the previous session. */
public enum SessionMode {
  /** No option: restores when the command line names no file and no comparison. */
  DEFAULT,
  /** {@code --session}: restores, also with files. */
  RESTORE,
  /** {@code --no-session}: starts without the previous session. */
  SKIP
}
