package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;

/**
 * The {@link MemoryBudget} charge of one tab's renders above the size limit: {@value
 * #BYTES_PER_CHAR} bytes per char, reserved before such a render starts and held while its result
 * shows. Confined to the UI thread.
 */
final class MarkdownCharge {
  /** Heap reserved per char for a render above the size limit; assumed, not measured. */
  static final long BYTES_PER_CHAR = 24;

  private final MemoryBudget budget;
  private final int limit;
  private long shown;

  /**
   * Creates the charge.
   *
   * @param budget the budget charged
   * @param limit the longest document, in chars, rendered without a charge
   */
  MarkdownCharge(MemoryBudget budget, int limit) {
    this.budget = budget;
    this.limit = limit;
  }

  /**
   * Reserves a render's charge.
   *
   * @param chars the document's length
   * @return the charge, 0 at or below the limit, or -1 when the budget refuses it
   */
  long reserve(int chars) {
    if (chars <= limit) {
      return 0;
    }
    long charge = BYTES_PER_CHAR * chars;
    return budget.reserve(charge) ? charge : -1;
  }

  /** Releases the charge of a render that does not show. */
  void release(long charge) {
    budget.release(charge);
  }

  /** Holds a shown render's charge and releases the one it replaces. */
  void shown(long charge) {
    budget.release(shown);
    shown = charge;
  }

  /** Releases the shown render's charge. */
  void dispose() {
    shown(0);
  }
}
