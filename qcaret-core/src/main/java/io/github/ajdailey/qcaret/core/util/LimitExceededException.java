package io.github.ajdailey.qcaret.core.util;

import java.io.IOException;

/** A bounded read met more than its limit. The message names the limit and never a path. */
public final class LimitExceededException extends IOException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param limit the most bytes the read accepted
   */
  public LimitExceededException(long limit) {
    super("More than " + limit + " bytes");
  }
}
