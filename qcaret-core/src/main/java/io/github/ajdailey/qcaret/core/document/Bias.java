package io.github.ajdailey.qcaret.core.document;

/** Which side of text inserted exactly at a position the position ends up on. */
public enum Bias {
  /** The position stays before the inserted text. */
  BEFORE,
  /** The position moves past the inserted text. */
  AFTER
}
