package io.github.ajdailey.qcaret.core.document;

/**
 * The heap a file load is estimated to need ({@link DocumentFiles#estimate}).
 *
 * @param fileBytes the file's size
 * @param textBytes the document's text
 * @param peakBytes held while the load runs: the text and the load's block buffers
 * @param retainedBytes held while the document is open: its text and the undo headroom
 */
public record LoadCost(long fileBytes, long textBytes, long peakBytes, long retainedBytes) {
  /**
   * Returns the larger of the two costs, the amount a load reserves before it starts.
   *
   * @return bytes
   */
  public long reserveBytes() {
    return Math.max(peakBytes, retainedBytes);
  }
}
