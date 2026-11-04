package io.github.ajdailey.qcaret.core.concurrent;

import java.util.concurrent.CancellationException;

/** Cancellation flag for one operation. Workers check it at their own granularity. */
public final class CancellationToken {
  private volatile boolean canceled;

  /** Sets the flag; later checks fail. */
  public void cancel() {
    canceled = true;
  }

  /**
   * Reports the flag.
   *
   * @return true once {@link #cancel} has run
   */
  public boolean isCanceled() {
    return canceled;
  }

  /**
   * Throws once canceled.
   *
   * @throws CancellationException after {@link #cancel}
   */
  public void check() {
    if (canceled) {
      throw new CancellationException("Operation canceled");
    }
  }
}
