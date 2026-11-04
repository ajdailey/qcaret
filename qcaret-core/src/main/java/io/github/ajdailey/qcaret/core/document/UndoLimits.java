package io.github.ajdailey.qcaret.core.document;

/**
 * Bounds of a document's undo history. Past either bound the oldest steps drop; the newest step is
 * always kept.
 *
 * @param maxSteps undo and redo steps kept
 * @param maxBytes removed and inserted text kept, at 2 bytes per char
 */
public record UndoLimits(int maxSteps, long maxBytes) {
  /** {@code undo.maxTransactions} 10,000 and {@code undo.maxBytes} 64 MiB. */
  public static final UndoLimits DEFAULTS = new UndoLimits(10_000, 64L * 1024 * 1024);

  /** Checks the components. */
  public UndoLimits {
    if (maxSteps < 1 || maxBytes < 0) {
      throw new IllegalArgumentException("maxSteps below 1 or negative maxBytes");
    }
  }
}
