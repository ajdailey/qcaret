package io.github.ajdailey.qcaret.app.cli;

/** An unknown option or a missing or bad value. */
public final class CommandLineException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message one line naming the argument and the problem
   */
  public CommandLineException(String message) {
    super(message);
  }
}
