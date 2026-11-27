package io.github.ajdailey.qcaret.app.view;

/** What a hex view's Copy puts on the clipboard. */
public enum HexCopy {
  /** {@link #HEX} from the hex column, {@link #TEXT} from the text column. */
  FOCUSED,
  /** The selected bytes as space-separated pairs: {@code 4D 5A 90 00}. */
  HEX,
  /** The selected bytes decoded in the source's charset. */
  TEXT,
  /** The caret's offset in hex. */
  OFFSET
}
