package io.github.ajdailey.qcaret.app.view;

/** A caret move in a hex view. */
public enum HexKey {
  /** One byte back. */
  LEFT,
  /** One byte on. */
  RIGHT,
  /** One row up. */
  UP,
  /** One row down. */
  DOWN,
  /** One page up. */
  PAGE_UP,
  /** One page down. */
  PAGE_DOWN,
  /** The caret row's first byte. */
  ROW_START,
  /** The caret row's last byte; extending, the end of the row. */
  ROW_END,
  /** The first byte. */
  SOURCE_START,
  /** The end of the source. */
  SOURCE_END
}
