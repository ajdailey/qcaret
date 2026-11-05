package io.github.ajdailey.qcaret.core.search;

/**
 * A regex overflowed the thread's stack while matching, as a deeply recursive pattern such as
 * {@code (a|b)*c} does on a long run of text. The search ends with it.
 */
public final class PatternTooComplexException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int offset;

  /**
   * Creates the exception.
   *
   * @param offset where the search that overflowed started
   */
  public PatternTooComplexException(int offset) {
    super("The pattern overflowed the stack matching from offset " + offset, null, false, false);
    this.offset = offset;
  }

  /**
   * Returns where the search that overflowed started.
   *
   * @return the offset
   */
  public int offset() {
    return offset;
  }
}
