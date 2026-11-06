package io.github.ajdailey.qcaret.core.document;

import java.io.IOException;

/** A running load stopped: its text costs more than the estimate and the budget has no room. */
public final class LoadTooLargeException extends IOException {
  private static final long serialVersionUID = 1L;

  private final transient LoadCost cost;

  /**
   * Creates the exception.
   *
   * @param cost the cost projected from the text read so far
   */
  public LoadTooLargeException(LoadCost cost) {
    super("Not enough memory: the file needs about " + cost.reserveBytes() + " bytes");
    this.cost = cost;
  }

  /**
   * Returns the cost projected from the text read so far.
   *
   * @return the cost
   */
  public LoadCost cost() {
    return cost;
  }
}
