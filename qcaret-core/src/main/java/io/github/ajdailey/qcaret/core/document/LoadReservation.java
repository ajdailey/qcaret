package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;

/**
 * A load's hold on the {@link MemoryBudget}. It starts at the estimate and rises when the text read
 * so far costs more than the estimate allows at that point in the file: {@link
 * DocumentFiles#estimate} samples only the head, so an ASCII head can hide 2 bytes per char later.
 * A rise the budget refuses ends the load with a {@link LoadTooLargeException}. The owner calls
 * {@link #close} in a {@code finally} block, which releases whatever {@link #keep} did not take.
 * Used by one thread.
 */
public final class LoadReservation implements AutoCloseable {
  /** Charges nothing and refuses nothing, for a load outside the budget. */
  public static final LoadReservation UNMETERED = new LoadReservation(null, null, 0);

  private final MemoryBudget budget;
  private final LoadCost cost;
  private final long base;
  private long held;

  private LoadReservation(MemoryBudget budget, LoadCost cost, long bytes) {
    this.budget = budget;
    this.cost = cost;
    this.base = bytes;
    this.held = bytes;
  }

  /**
   * Reserves a load's {@link LoadCost#reserveBytes}.
   *
   * @param budget the budget
   * @param cost the estimate
   * @return the reservation, or null, reserving nothing, when the budget cannot hold it
   */
  public static LoadReservation reserve(MemoryBudget budget, LoadCost cost) {
    return reserve(budget, cost, cost.reserveBytes());
  }

  /**
   * Reserves a given amount for a load.
   *
   * @param budget the budget
   * @param cost the estimate
   * @param bytes the amount
   * @return the reservation, or null, reserving nothing, when the budget cannot hold it
   */
  public static LoadReservation reserve(MemoryBudget budget, LoadCost cost, long bytes) {
    return budget.reserve(bytes) ? new LoadReservation(budget, cost, bytes) : null;
  }

  /**
   * Checks the text read so far against the estimate and raises the reservation by any excess.
   *
   * @param heapBytes the heap the text takes so far
   * @param fileBytes the file bytes it came from
   * @throws LoadTooLargeException when the budget cannot hold the raise
   */
  public void charge(long heapBytes, long fileBytes) throws LoadTooLargeException {
    if (budget == null) {
      return;
    }
    double ratio = cost.fileBytes() == 0 ? 0 : (double) cost.textBytes() / cost.fileBytes();
    long excess = heapBytes - (long) (fileBytes * ratio);
    long raise = base + excess - held;
    if (raise > 0) {
      if (!budget.reserve(raise)) {
        throw new LoadTooLargeException(project(heapBytes, fileBytes, ratio));
      }
      held += raise;
    }
  }

  /**
   * Holds {@code bytes} more for the rest of the load.
   *
   * @param bytes at least 0
   * @throws LoadTooLargeException when the budget cannot hold them
   */
  void add(long bytes) throws LoadTooLargeException {
    if (budget == null) {
      return;
    }
    if (!budget.reserve(bytes)) {
      throw new LoadTooLargeException(cost);
    }
    held += bytes;
  }

  /** Scales the estimate to the rate of the text so far, where that is the higher one. */
  private LoadCost project(long heapBytes, long fileBytes, double ratio) {
    double rate = Math.max(ratio, (double) heapBytes / Math.max(1, fileBytes));
    long text = heapBytes + (long) ((cost.fileBytes() - fileBytes) * rate);
    long more = text - cost.textBytes();
    return new LoadCost(
        cost.fileBytes(), text, cost.peakBytes() + more, cost.retainedBytes() + more);
  }

  /**
   * Ends the load: lowers the reservation to what the document retains and passes that to the
   * caller, who releases it when the document closes.
   *
   * @param retainedBytes {@link LoadedFile#retainedBytes}
   * @return the bytes the caller now owns
   */
  public long keep(long retainedBytes) {
    if (budget == null) {
      return 0;
    }
    long kept = Math.min(held, retainedBytes);
    budget.release(held - kept);
    held = 0;
    return kept;
  }

  /** Releases what is still held. */
  @Override
  public void close() {
    if (budget != null) {
      budget.release(held);
      held = 0;
    }
  }
}
