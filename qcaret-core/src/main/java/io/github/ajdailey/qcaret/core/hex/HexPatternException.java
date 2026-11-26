package io.github.ajdailey.qcaret.core.hex;

/** A find pattern that {@link HexPattern} cannot use, with the reason and where in the input. */
public final class HexPatternException extends Exception {
  private static final long serialVersionUID = 1L;

  /** Why a pattern was refused. */
  public enum Reason {
    /** No bytes. */
    EMPTY,
    /** A hex digit without its pair. */
    ODD_DIGITS,
    /** A char that is not a hex digit, {@code ?} pair or space. */
    NOT_HEX,
    /** Only {@code ??} wildcards, which match at every offset. */
    WILDCARDS_ONLY,
    /** More than {@link HexPattern#MAX_LENGTH} bytes. */
    TOO_LONG,
    /** A char the source's charset cannot encode. */
    UNMAPPABLE
  }

  private final Reason reason;
  private final int index;

  /**
   * Creates the exception.
   *
   * @param reason why
   * @param index the char index in the input, or -1 for the whole input
   */
  public HexPatternException(Reason reason, int index) {
    super(reason + (index >= 0 ? " at " + index : ""));
    this.reason = reason;
    this.index = index;
  }

  /**
   * Returns why the pattern was refused.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns where in the input.
   *
   * @return the char index, or -1 for the whole input
   */
  public int index() {
    return index;
  }
}
