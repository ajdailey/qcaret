package io.github.ajdailey.qcaret.core.document;

/**
 * One replacement of an {@link EditBatch}, in the coordinates of the text before the batch.
 *
 * @param offset the first offset replaced
 * @param removeLength the chars removed
 * @param text the chars inserted, possibly empty
 */
public record Replacement(int offset, int removeLength, String text) {
  /** Checks the components. */
  public Replacement {
    if (offset < 0 || removeLength < 0) {
      throw new IllegalArgumentException("negative offset or length");
    }
    if (text == null) {
      throw new IllegalArgumentException("null text");
    }
  }

  /**
   * Returns the offset after the removed range.
   *
   * @return {@code offset + removeLength}
   */
  public int end() {
    return offset + removeLength;
  }
}
