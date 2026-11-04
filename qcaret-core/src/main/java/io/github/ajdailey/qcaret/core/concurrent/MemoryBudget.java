package io.github.ajdailey.qcaret.core.concurrent;

import io.github.ajdailey.qcaret.core.util.Checks;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The share of the maximum heap that open documents draw from. A load reserves its estimated cost
 * before it reads the file and releases it when the document closes; a refused reservation refuses
 * the load instead of running into an {@link OutOfMemoryError}. Thread-safe.
 */
public final class MemoryBudget {
  /** Percent of {@code Runtime.maxMemory()} that documents may hold. */
  public static final int DOCUMENT_PERCENT = 60;

  private final long maxHeap;
  private final long limit;
  private final AtomicLong used = new AtomicLong();

  /**
   * Creates a budget.
   *
   * @param maxHeap the maximum heap size the share is taken from
   * @param percent the share, from 1 to 100
   */
  public MemoryBudget(long maxHeap, int percent) {
    Checks.notNegative(maxHeap, "maxHeap");
    Checks.atLeast(percent, 1, "percent");
    if (percent > 100) {
      throw new IllegalArgumentException("percent must be at most 100");
    }
    this.maxHeap = maxHeap;
    this.limit = maxHeap / 100 * percent;
  }

  /**
   * Creates the documents' budget from {@code Runtime.maxMemory()} and {@link #DOCUMENT_PERCENT}.
   *
   * @return the budget
   */
  public static MemoryBudget fromRuntime() {
    return new MemoryBudget(Runtime.getRuntime().maxMemory(), DOCUMENT_PERCENT);
  }

  /**
   * Returns the maximum heap size the share is taken from.
   *
   * @return bytes
   */
  public long maxHeap() {
    return maxHeap;
  }

  /**
   * Returns the share's size.
   *
   * @return bytes
   */
  public long limit() {
    return limit;
  }

  /**
   * Reserves bytes.
   *
   * @param bytes at least 0
   * @return false, reserving nothing, when the share cannot hold them
   */
  public boolean reserve(long bytes) {
    Checks.notNegative(bytes, "bytes");
    while (true) {
      long current = used.get();
      if (bytes > limit - current) {
        return false;
      }
      if (used.compareAndSet(current, current + bytes)) {
        return true;
      }
    }
  }

  /**
   * Returns bytes reserved earlier.
   *
   * @param bytes at most what the caller holds
   */
  public void release(long bytes) {
    Checks.notNegative(bytes, "bytes");
    used.addAndGet(-bytes);
  }

  /**
   * Returns what the share can still hold.
   *
   * @return bytes
   */
  public long available() {
    return Math.max(0, limit - used.get());
  }

  /**
   * Returns what is reserved.
   *
   * @return bytes
   */
  public long reserved() {
    return used.get();
  }
}
