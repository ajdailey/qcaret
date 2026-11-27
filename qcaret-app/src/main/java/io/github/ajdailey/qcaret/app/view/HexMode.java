package io.github.ajdailey.qcaret.app.view;

/** What an editor tab shows of its hex pane. A tab never shows the hex pane and a preview. */
public enum HexMode {
  /** The editor alone. */
  TEXT,
  /** The editor with the hex pane on its right, carets and selections synced. */
  SPLIT,
  /** The hex pane alone. */
  HEX
}
