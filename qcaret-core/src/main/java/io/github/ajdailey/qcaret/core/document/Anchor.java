package io.github.ajdailey.qcaret.core.document;

/**
 * A live position in a {@link Document}, mapped through every change with its {@link Bias}:
 * bookmarks, fold starts, navigation history. Read and dispose it on the document's thread.
 */
public final class Anchor {
  private final Document document;
  private final Bias bias;
  private int offset;
  private boolean disposed;

  Anchor(Document document, int offset, Bias bias) {
    this.document = document;
    this.offset = offset;
    this.bias = bias;
  }

  /**
   * Returns the current offset.
   *
   * @return the offset
   */
  public int offset() {
    return offset;
  }

  /**
   * Returns the bias.
   *
   * @return the side of an insert at the anchor it stays on
   */
  public Bias bias() {
    return bias;
  }

  /**
   * Reports whether {@link #dispose} has run.
   *
   * @return true once disposed
   */
  public boolean isDisposed() {
    return disposed;
  }

  /** Stops mapping the anchor; its offset keeps its last value. */
  public void dispose() {
    if (!disposed) {
      disposed = true;
      document.removeAnchor(this);
    }
  }

  void map(ChangeMap map) {
    offset = map.map(offset, bias);
  }
}
