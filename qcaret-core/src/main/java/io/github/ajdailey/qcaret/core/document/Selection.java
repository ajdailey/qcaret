package io.github.ajdailey.qcaret.core.document;

/**
 * A selected range, or a caret when empty. The caret is the end that moves.
 *
 * @param anchor the end that stays put
 * @param caret the end that moves
 * @param preferredColumn the visual column vertical moves aim for, or -1 for none
 */
public record Selection(int anchor, int caret, int preferredColumn) {
  /** Checks the components. */
  public Selection {
    if (anchor < 0 || caret < 0 || preferredColumn < -1) {
      throw new IllegalArgumentException("negative offset or column");
    }
  }

  /**
   * Creates a caret with no preferred column.
   *
   * @param offset the caret offset
   * @return the empty selection
   */
  public static Selection caret(int offset) {
    return new Selection(offset, offset, -1);
  }

  /**
   * Creates a selection with no preferred column.
   *
   * @param anchor the end that stays put
   * @param caret the end that moves
   * @return the selection
   */
  public static Selection of(int anchor, int caret) {
    return new Selection(anchor, caret, -1);
  }

  /**
   * Returns the lower end.
   *
   * @return {@code min(anchor, caret)}
   */
  public int start() {
    return Math.min(anchor, caret);
  }

  /**
   * Returns the upper end.
   *
   * @return {@code max(anchor, caret)}
   */
  public int end() {
    return Math.max(anchor, caret);
  }

  /**
   * Reports whether the selection is a caret.
   *
   * @return true when anchor and caret are equal
   */
  public boolean isEmpty() {
    return anchor == caret;
  }

  /** Maps both ends; an empty selection moves past an insert at it, a range does not grow. */
  Selection map(ChangeMap map) {
    if (isEmpty()) {
      int at = map.map(caret, Bias.AFTER);
      return new Selection(at, at, preferredColumn);
    }
    int start = map.map(start(), Bias.AFTER);
    int end = Math.max(start, map.map(end(), Bias.BEFORE));
    return anchor <= caret
        ? new Selection(start, end, preferredColumn)
        : new Selection(end, start, preferredColumn);
  }
}
