package io.github.ajdailey.qcaret.app.view;

/** The answer to the binary file prompt. */
public enum BinaryChoice {
  /** Open the file in a Hex Viewer window; the default button. */
  HEX_VIEWER,
  /** Open a read-only ISO-8859-1 tab. */
  TEXT,
  /** Open nothing; closing the prompt answers this. */
  CANCEL
}
