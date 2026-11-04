package io.github.ajdailey.qcaret.core.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the operations using a resource so its owner can close it only after they end. An
 * operation calls {@link #enter} before it touches the resource and {@link #exit} when it is done;
 * after {@link #close}, {@code enter} returns false and {@link #awaitIdle} returns once the count
 * reaches zero. Lock-free, so the UI thread may call {@code enter}; only {@code awaitIdle} waits.
 */
public final class OperationGate {
  private static final long CLOSED = Long.MIN_VALUE;

  private final AtomicLong state = new AtomicLong();
  private final CountDownLatch idle = new CountDownLatch(1);

  /**
   * Admits an operation unless the gate is closed.
   *
   * @return true when admitted; the caller then calls {@link #exit} exactly once
   */
  public boolean enter() {
    while (true) {
      long current = state.get();
      if (current < 0) {
        return false;
      }
      if (state.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  /** Ends an admitted operation. */
  public void exit() {
    if (state.decrementAndGet() == CLOSED) {
      idle.countDown();
    }
  }

  /** Refuses later operations; operations already admitted continue. Idempotent. */
  public void close() {
    while (true) {
      long current = state.get();
      if (current < 0) {
        return;
      }
      if (state.compareAndSet(current, current | CLOSED)) {
        if (current == 0) {
          idle.countDown();
        }
        return;
      }
    }
  }

  /**
   * Reports whether {@link #close} has run.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return state.get() < 0;
  }

  /**
   * Counts admitted operations that have not exited.
   *
   * @return the count
   */
  public long active() {
    return state.get() & Long.MAX_VALUE;
  }

  /**
   * Waits for the admitted operations to exit after {@link #close}. Never call it on the UI thread.
   *
   * @param timeoutMillis the longest wait
   * @return true when the gate is closed and no operation remains
   * @throws InterruptedException when interrupted while waiting
   */
  public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
    return idle.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }
}
