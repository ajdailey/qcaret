package io.github.ajdailey.qcaret.core.concurrent;

/** Control of one background operation. */
public interface OperationHandle {
  /** Requests cancellation; the operation stops at its next check and reports {@code CANCELED}. */
  void cancel();

  /**
   * Reports whether the operation has ended and notified its listener.
   *
   * @return true after the final listener call
   */
  boolean isDone();

  /**
   * Waits for the operation to end. Never call it on the UI thread.
   *
   * @param timeoutMillis the longest wait
   * @return false on timeout
   * @throws InterruptedException when interrupted while waiting
   */
  boolean await(long timeoutMillis) throws InterruptedException;
}
