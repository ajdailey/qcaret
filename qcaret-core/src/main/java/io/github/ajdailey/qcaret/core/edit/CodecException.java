package io.github.ajdailey.qcaret.core.edit;

/** A {@link TextCodec} refused its input; the text stays unconverted. */
public final class CodecException extends Exception {
  private static final long serialVersionUID = 1L;

  /** Why the input was refused. */
  public enum Reason {
    /** Base64 decode met a char outside the alphabet, misplaced padding or a short last unit. */
    NOT_BASE64,
    /** The decoded bytes are not valid UTF-8. */
    NOT_UTF8,
    /** The text to encode holds a surrogate without its pair, which has no UTF-8 form. */
    UNPAIRED_SURROGATE,
    /** A {@code %} not followed by two hex digits, or a JSON backslash escape that is not valid. */
    MALFORMED_ESCAPE,
    /** A JSON backslash-u escape of a surrogate without an escaped pair beside it. */
    LONE_SURROGATE_ESCAPE
  }

  /** The reason. */
  private final Reason reason;

  /** The 1-based index of the refused selection among the non-empty ones, or 0. */
  private final int selection;

  /**
   * Creates the exception for a text.
   *
   * @param reason the reason
   */
  public CodecException(Reason reason) {
    this(reason, 0);
  }

  /**
   * Creates the exception for a selection.
   *
   * @param reason the reason
   * @param selection the 1-based index of the refused selection among the non-empty ones
   */
  public CodecException(Reason reason, int selection) {
    super(reason.name());
    this.reason = reason;
    this.selection = selection;
  }

  /**
   * Returns the reason.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the refused selection.
   *
   * @return its 1-based index among the non-empty selections in text order, or 0 for a text
   */
  public int selection() {
    return selection;
  }
}
