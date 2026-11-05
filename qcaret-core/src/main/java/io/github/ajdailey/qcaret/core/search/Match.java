package io.github.ajdailey.qcaret.core.search;

/**
 * A non-empty match.
 *
 * @param start the offset of its first char
 * @param end the offset after its last char
 */
public record Match(int start, int end) {
  /** Checks the components. */
  public Match {
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("empty or negative match");
    }
  }
}
